package workflows4s.runtime

import cats.Monad
import cats.effect.LiftIO
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*

import java.time.{Clock, Instant}

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]
  protected given liftIO: LiftIO[F]

  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def updateState(event: Option[WCEvent[Ctx]], workflow: ActiveWorkflow[Ctx]): F[Unit]
  protected def knockerUpper: KnockerUpper.Agent.Curried
  protected def clock: Clock

  override def queryState(): F[WCState[Ctx]] = {
    for {
      wf <- getWorkflow
      now = Instant.now
    } yield wf.liveState(now)
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    for {
      state  <- getWorkflow
      now     = Instant.now(clock)
      result <- state.handleSignal(signalDef)(req, now) match {
                  case Some(resultIO) =>
                    for {
                      result       <- liftIO.liftIO(resultIO)
                      (event, resp) = result
                      _            <- handleLiveEvent(event)
                    } yield resp.asRight
                  case None           => UnexpectedSignal(signalDef).asLeft.pure[F]
                }
    } yield result
  }

  override def wakeup(): F[Unit] =
    for {
      state <- getWorkflow
      now    = Instant.now(clock)
      _     <- state.proceed(now) match {
                 case Some(resultIO) => liftIO.liftIO(resultIO).flatMap(handleLiveEvent)
                 case None           => fMonad.unit
               }
    } yield ()

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = getWorkflow.map(_.wio.toProgress)

  protected def handleLiveEvent(event: WCEvent[Ctx]): F[Unit] = {
    for {
      state      <- getWorkflow
      now         = Instant.now(clock)
      newStateOpt = state.handleEvent(event, now)
      newState    = newStateOpt match {
                      case Some(value) => value
                      case None        => throw new Exception("Event was not handled") // TODO shouldn't be the case for recovery
                    }
      _          <- updateState(event.some, newState)
      _          <- if (state.wakeupAt != newState.wakeupAt) liftIO.liftIO(knockerUpper.updateWakeup((), newState.wakeupAt)) else fMonad.unit
      _          <- wakeup()
    } yield ()
  }

  protected def recover(events: Seq[WCEvent[Ctx]]): F[Unit] = {
    for {
      state   <- getWorkflow
      now      = Instant.now(clock)
      newState = events.foldLeft(state)((state, event) => {
                   state.handleEvent(event, now) match {
                     case Some(value) => value
                     case None        =>
                       logger.warn(s"Ignored event ${}")
                       state
                   }
                 })
      _       <- updateState(None, newState)
      _       <- wakeup()
    } yield ()
  }
}
