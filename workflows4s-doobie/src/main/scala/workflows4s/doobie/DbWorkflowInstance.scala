package workflows4s.doobie

import cats.Applicative
import cats.effect.kernel.Resource
import cats.effect.{IO, LiftIO, Sync}
import cats.syntax.all.*
import doobie.{ConnectionIO, WeakAsync}
import doobie.implicits.*
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.{Clock, Instant}
import scala.annotation.tailrec

class DbWorkflowInstance[Ctx <: WorkflowContext, Id](
    id: Id,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[Id],
    eventCodec: EventCodec[WCEvent[Ctx]],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[Id],
) extends WorkflowInstance[[t] =>> Resource[IO, ConnectionIO[t]], WCState[Ctx]] {

  private def withLiftIO[T](thunk: LiftIO[ConnectionIO] => ConnectionIO[T]): Resource[IO, ConnectionIO[T]] =
    WeakAsync.liftIO[ConnectionIO].map(thunk)

  override def getProgress: Resource[IO, ConnectionIO[WIOExecutionProgress[WCState[Ctx]]]] = Resource.pure {
    restoreWorkflow.map(_.wio.toProgress)
  }

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Resource[IO, ConnectionIO[Either[UnexpectedSignal, Resp]]] = withLiftIO {
    liftIO =>
      storage
        .lockWorkflow(id)
        .use(_ =>
          for {
            wf     <- restoreWorkflow
            now    <- Sync[ConnectionIO].delay(clock.instant())
            result <- wf.handleSignal(signalDef)(req, now) match {
                        case Some(eventIO) =>
                          for {
                            (event, resp) <- liftIO.liftIO(eventIO)
                            _             <- storage.saveEvent(id, eventCodec.write(event))
                            newWf          = handleEvents(wf, List(event), now)
                            _             <- proceed(newWf, now, liftIO)
                          } yield resp.asRight
                        case None          => Sync[ConnectionIO].pure(UnexpectedSignal(signalDef).asLeft)
                      }
          } yield result,
        )
  }

  def queryState(): Resource[IO, ConnectionIO[WCState[Ctx]]] = {
    Resource.pure {
      for {
        wf  <- restoreWorkflow
        now <- Sync[ConnectionIO].delay(clock.instant())
      } yield wf.liveState(now)
    }
  }

  def wakeup(): Resource[IO, ConnectionIO[Unit]] = withLiftIO { liftIO =>
    storage
      .lockWorkflow(id)
      .use(_ =>
        for {
          wf  <- restoreWorkflow
          now <- Sync[ConnectionIO].delay(clock.instant())
          _   <- proceed(wf, now, liftIO)
        } yield (),
      )
  }

  private def proceed(wf: ActiveWorkflow[Ctx], now: Instant, liftIO: LiftIO[ConnectionIO]): ConnectionIO[Unit] = {
    wf.proceed(now) match {
      case Some(eventIO) =>
        for {
          event <- liftIO.liftIO(eventIO)
          _     <- storage.saveEvent(id, eventCodec.write(event))
          newWf  = handleEvents(wf, List(event), now)
          _     <- if (newWf.wakeupAt != wf.wakeupAt)
                     liftIO.liftIO(knockerUpper.updateWakeup(id, newWf.wakeupAt)) // TODO should we really rollback tx is this fails?
                   else Sync[ConnectionIO].unit
          _     <- proceed(newWf, now, liftIO)
        } yield ()
      case None          => Applicative[ConnectionIO].pure(())
    }
  }

  private def queryEvents: ConnectionIO[List[WCEvent[Ctx]]] = {
    storage.getEvents(id).flatMap(_.traverse(eventBytes => Sync[ConnectionIO].fromTry(eventCodec.read(eventBytes))))
  }

  private def restoreWorkflow: ConnectionIO[ActiveWorkflow[Ctx]] = for {
    events <- queryEvents
    now    <- Sync[ConnectionIO].delay(clock.instant())
  } yield handleEvents(baseWorkflow, events, now)

  @tailrec
  private def handleEvents(wf: ActiveWorkflow[Ctx], events: List[WCEvent[Ctx]], now: Instant): ActiveWorkflow[Ctx] = {
    if (events.isEmpty) wf
    else {
      wf.handleEvent(events.head, now) match {
        case Some(newWf) => handleEvents(newWf, events.tail, now)
        case None        => wf
      }
    }
  }
}
