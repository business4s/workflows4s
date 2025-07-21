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
  protected def knockerUpper: KnockerUpper.Agent
  protected def clock: Clock
  protected def registry: WorkflowRegistry.Agent

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

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] = {
    for {
      wf  <- getWorkflow
      now <- currentTime
    } yield wf.liveSignals(now)
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx], now: Instant): F[LockOutcome[Either[WorkflowInstance.UnexpectedSignal, Resp]]] = {
      state.handleSignal(signalDef)(req, now) match {
        case Some(resultIO) =>
          for {
            result       <- liftIO.liftIO(resultIO)
            _             = logger.debug(s"Delivering signal produced result: ${result}")
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
      _           <- postEventActions(stateUpdate.map(_ => None))
    } yield stateUpdate.result
  }

  override def wakeup(): F[Unit] = {
    def processWakeup(state: ActiveWorkflow[Ctx]) = for {
      now    <- currentTime
      result <- state.proceed(now) match {
                  case Some(resultIO) =>
                    for {
                      _            <- registerRunningInstance
                      retryOrEvent <- liftIO.liftIO(resultIO)
                      _             = logger.debug(s"Waking up the instance produced an event: ${retryOrEvent}")
                    } yield retryOrEvent match {
                      case Left(retryTime) => LockOutcome.NoOp(Some(retryTime))
                      case Right(event)    => LockOutcome.NewEvent(event, None)
                    }
                  case None           =>
                    logger.debug(s"Waking up the instance didn't produce an event")
                    for {
                      _ <- registerNotRunningInstance(state)
                    } yield LockOutcome.NoOp(None)
                }
    } yield result

    for {
      _           <- fMonad.unit
      _            = logger.trace(s"Waking up the instance.")
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

  private type RetryTime = Instant
  private def postEventActions(update: StateUpdate[Option[RetryTime]]): F[Unit] = {
    update match {
      case StateUpdate.Updated(oldState, newState, _) =>
        for {
          _ <- if newState.wakeupAt != oldState.wakeupAt then updateWakeup(newState.wakeupAt)
               else fMonad.unit
          _ <- wakeup()
        } yield ()
      case StateUpdate.NoOp(oldState, retryTimeOpt)   =>
        retryTimeOpt match {
          case Some(retryTime) =>
            if oldState.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(Some(retryTime))
            else fMonad.unit
          case None            => fMonad.unit
        }
    }
  }

  private def updateWakeup(time: Option[Instant]) = {
    liftIO.liftIO(
      knockerUpper
        .updateWakeup(this.id, time)
        .handleError(err => {
          logger.error("Failed to register wakeup", err)
        }),
    )
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
    registry.upsertInstance(this.id, WorkflowRegistry.ExecutionStatus.Running).pipe(liftIO.liftIO)
  }
  private def registerNotRunningInstance(state: ActiveWorkflow[Ctx]): F[Unit] = {
    val status =
      if state.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(this.id, status).pipe(liftIO.liftIO)
  }

  enum LockOutcome[+T] {
    case NewEvent(event: WCEvent[Ctx], result: T)
    case NoOp(result: T)
  }

  enum StateUpdate[+T] {
    case Updated(oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx], result: T)
    case NoOp(oldState: ActiveWorkflow[Ctx], result: T)

    def result: T

    def map[U](f: T => U): StateUpdate[U] = this match {
      case Updated(oldState, newState, result) => Updated(oldState, newState, f(result))
      case NoOp(oldState, result)              => NoOp(oldState, f(result))
    }

  }
}
