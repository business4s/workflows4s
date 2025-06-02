package workflows4s.runtime

import cats.Monad
import cats.effect.LiftIO
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.{Clock, Instant}
import scala.util.chaining.scalaUtilChainingOps

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]
  protected given liftIO: LiftIO[F]

  protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => F[LockOutcome[T]]): F[StateUpdate[T]]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def knockerUpper: KnockerUpper.Agent.Curried
  protected def clock: Clock
  protected def registry: WorkflowRegistry.Agent.Curried

  override def queryState(): F[WCState[Ctx]] = {
    for {
      wf  <- getWorkflow
      now <- currentTime
    } yield wf.liveState(now)
  }

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = {
    for {
      wf  <- getWorkflow
      now <- currentTime
    } yield wf.progress(now)
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx], now: Instant): F[LockOutcome[Either[WorkflowInstance.UnexpectedSignal, Resp]]] = {
      state.handleSignal(signalDef)(req, now) match {
        case Some(resultIO) =>
          for {
            result       <- liftIO.liftIO(resultIO)
            (event, resp) = result
          } yield LockOutcome.NewEvent(event, resp.asRight)
        case None           =>
          LockOutcome.NoOp(UnexpectedSignal(signalDef).asLeft).pure[F]
      }
    }
    for {
      now         <- currentTime
      _            = logger.debug(s"Delivering signal $signalDef")
      stateUpdate <- lockAndUpdateState(processSignal(_, now))
      _           <- postEventActions(stateUpdate)
    } yield stateUpdate.result
  }

  override def wakeup(): F[Unit] = {
    def processWakeup(state: ActiveWorkflow[Ctx]) = for {
      now    <- currentTime
      result <- state.proceed(now) match {
                  case Some(resultIO) =>
                    for {
                      _     <- registerRunningInstance
                      event <- liftIO.liftIO(resultIO)
                      _      = logger.debug(s"Waking up the instance. Produced event: ${event}")
                    } yield LockOutcome.NewEvent(event, ())
                  case None           =>
                    logger.debug(s"Waking up the instance didn't produce an event")
                    for {
                      _ <- registerNotRunningInstance(state)
                    } yield LockOutcome.NoOp(())
                }
    } yield result

    for {
      stateUpdate <- lockAndUpdateState(processWakeup)
      _           <- postEventActions(stateUpdate)
    } yield ()
  }

  protected def currentTime: F[Instant] = fMonad.unit.map(_ => clock.instant())

  protected def processLiveEvent(event: WCEvent[Ctx], state: ActiveWorkflow[Ctx], now: Instant): ActiveWorkflow[Ctx] = {
    state.handleEvent(event, now) match {
      case Some(value) => value
      case None        => throw new Exception("Event was not handled")
    }
  }

  protected def postEventActions(update: StateUpdate[?]): F[Unit] = {
    update match {
      case StateUpdate.Updated(oldState, newState, _) =>
        for {
          _ <- if newState.wakeupAt != oldState.wakeupAt then liftIO.liftIO(
                 knockerUpper
                   .updateWakeup((), newState.wakeupAt)
                   .handleError(err => {
                     logger.error("Failed to register wakeup", err)
                   }),
               )
               else fMonad.unit
          _ <- wakeup()
        } yield ()
      case StateUpdate.NoOp(_, _)                     => fMonad.unit
    }
  }

  protected def recover(initialState: ActiveWorkflow[Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[Ctx]] = {
    for {
      now     <- currentTime
      newState = events.foldLeft(initialState)((state, event) => {
                   state.handleEvent(event, now) match {
                     case Some(value) => value
                     case None        =>
                       logger.warn(s"Ignored event ${}")
                       state
                   }
                 })
    } yield newState
  }

  private def registerRunningInstance: F[Unit]                                = {
    registry.upsertInstance((), WorkflowRegistry.ExecutionStatus.Running).pipe(liftIO.liftIO)
  }
  private def registerNotRunningInstance(state: ActiveWorkflow[Ctx]): F[Unit] = {
    val status =
      if state.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance((), status).pipe(liftIO.liftIO)
  }

  enum LockOutcome[+T] {
    case NewEvent(event: WCEvent[Ctx], result: T)
    case NoOp(result: T)
  }

  enum StateUpdate[+T] {
    case Updated(oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx], result: T)
    case NoOp(oldState: ActiveWorkflow[Ctx], result: T)

    def result: T
  }
}
