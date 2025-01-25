package workflows4s.doobie

import java.time.{Clock, Instant}
import scala.annotation.tailrec
import cats.Applicative
import cats.effect.{IO, LiftIO, Sync}
import cats.syntax.all.*
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.model.WIOModel

class DbWorkflowInstance[Ctx <: WorkflowContext, Id](
    id: Id,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[Id],
    liftIO: LiftIO[ConnectionIO],
    eventCodec: EventCodec[WCEvent[Ctx]],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[Id],
) extends WorkflowInstance[ConnectionIO, WCState[Ctx]] {

  override def getModel: ConnectionIO[WIOModel] = restoreWorkflow.map(_.wio.toModel)

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): ConnectionIO[Either[UnexpectedSignal, Resp]] = {
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
                          _             <- proceed(newWf, now)
                        } yield resp.asRight
                      case None          => Sync[ConnectionIO].pure(UnexpectedSignal(signalDef).asLeft)
                    }
        } yield result,
      )
  }

  def queryState(): ConnectionIO[WCState[Ctx]] = {
    for {
      wf  <- restoreWorkflow
      now <- Sync[ConnectionIO].delay(clock.instant())
    } yield wf.liveState(now)
  }

  def wakeup(): ConnectionIO[Unit] = {
    storage
      .lockWorkflow(id)
      .use(_ =>
        for {
          wf  <- restoreWorkflow
          now <- Sync[ConnectionIO].delay(clock.instant())
          _   <- proceed(wf, now)
        } yield (),
      )
  }

  private def proceed(wf: ActiveWorkflow[Ctx], now: Instant): ConnectionIO[Unit] = {
    wf.proceed(now) match {
      case Some(eventIO) =>
        for {
          event <- liftIO.liftIO(eventIO)
          _     <- storage.saveEvent(id, eventCodec.write(event))
          newWf  = handleEvents(wf, List(event), now)
          _     <- if (newWf.wakeupAt != wf.wakeupAt)
                     liftIO.liftIO(knockerUpper.updateWakeup(id, newWf.wakeupAt)) // TODO should we really rollback tx is this fails?
                   else Sync[ConnectionIO].unit
          _     <- proceed(newWf, now)
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
