package workflows4s.runtime

import cats.Monad
import cats.effect.LiftIO
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.{Clock, Instant}

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]
  protected given liftIO: LiftIO[F]

  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]

  enum LockOutcome[+T] {
    case NewEvent(event: WCEvent[Ctx], result: T)
    case NoOp(result: T)
  }
  enum StateUpdate[+T] {
    case Updated(oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx], result: T)
    case NoOp(oldState: ActiveWorkflow[Ctx], result: T)

    def result: T
  }
  protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => F[LockOutcome[T]]): F[StateUpdate[T]]
  protected def knockerUpper: KnockerUpper.Agent.Curried
  protected def clock: Clock

  override def queryState(): F[WCState[Ctx]] = {
    for {
      wf <- getWorkflow
      now = Instant.now
    } yield wf.liveState(now)
  }

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = {
    for {
      wf <- getWorkflow
      now = Instant.now
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
    for {
      _           <- fMonad.unit
      _            = logger.trace(s"Waking up the workflow")
      stateUpdate <- lockAndUpdateState { state =>
                       for {
                         now    <- currentTime
                         result <- state.proceed(now) match {
                                     case Some(resultIO) =>
                                       for {
                                         event <- liftIO.liftIO(resultIO)
                                       } yield LockOutcome.NewEvent(event, ())
                                     case None           => LockOutcome.NoOp(()).pure[F]
                                   }
                       } yield result
                     }
      _           <- postEventActions(stateUpdate)
    } yield ()
  }

  protected def currentTime = fMonad.unit.map(_ => Instant.now(clock))

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
          _ <- if (newState.wakeupAt != oldState.wakeupAt)
                 liftIO.liftIO(
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
}
