package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext](using E: Effect[F]) extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  import Effect.*

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[F, Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[F, Ctx] => F[T]): F[T]
  protected def getWorkflow: F[ActiveWorkflow[F, Ctx]]
  protected def engine: WorkflowInstanceEngine[F]

  override def queryState(): F[WCState[Ctx]] =
    getWorkflow.flatMap(engine.queryState)

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] =
    getWorkflow.flatMap(engine.getProgress)

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] =
    getWorkflow.flatMap(engine.getExpectedSignals)

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[F, Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      engine.handleSignal(state, signalDef, req).flatMap {
        case SignalResult.Processed(resultF)           =>
          for {
            eventAndResp <- resultF
            _            <- persistEvent(eventAndResp.event)
            newStateOpt  <- engine.handleEvent(state, eventAndResp.event)
            _            <- newStateOpt.traverse_(updateState)
            _            <- handleStateChange(state, newStateOpt)
          } yield Right(eventAndResp.response)
        case _: SignalResult.UnexpectedSignal[?, ?, ?] =>
          E.pure(Left(WorkflowInstance.UnexpectedSignal(signalDef)))
      }
    }
    lockState(processSignal)
  }

  override def wakeup(): F[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[F, Ctx], newStateOpt: Option[ActiveWorkflow[F, Ctx]]): F[Unit] = {
    newStateOpt match {
      case Some(newState) =>
        for {
          cmds <- engine.onStateChange(oldState, newState)
          // Use E.traverse_ instead of cats .traverse
          _    <- E.traverse_(cmds.toList) { case PostExecCommand.WakeUp =>
                    processWakeup(newState)
                  }
        } yield ()
      case None           => E.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[F, Ctx]): F[Unit] = {
    engine.triggerWakeup(state).flatMap {
      case WakeupResult.Processed(resultF) =>
        for {
          res <- resultF
          _   <- res match {
                   case ProcessingResult.Proceeded(event) =>
                     for {
                       _           <- persistEvent(event)
                       newStateOpt <- engine.handleEvent(state, event)
                       _           <- newStateOpt.traverse_(updateState)
                       _           <- handleStateChange(state, newStateOpt)
                     } yield ()
                   case ProcessingResult.Delayed(at)      =>
                     // Logic to schedule a future wakeup in your infrastructure
                     E.unit
                   case ProcessingResult.Failed(err)      =>
                     // Log the actual error
                     logger.error("Workflow wakeup failed", err)
                     E.unit
                 }
        } yield ()
      case _: WakeupResult.Noop[?, ?]      => E.unit
    }
  }

  protected def recover(initialState: ActiveWorkflow[F, Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[F, Ctx]] = {
    // Manually implement foldLeftM logic using E.flatMap
    // Note: When handleEvent returns None, we keep the current workflow unchanged (matching main branch behavior).
    // This silently ignores unmatched events - a known limitation that should be fixed in EventEvaluator.
    def loop(currentWf: ActiveWorkflow[F, Ctx], remaining: List[WCEvent[Ctx]]): F[ActiveWorkflow[F, Ctx]] = {
      remaining match {
        case Nil          => E.pure(currentWf)
        case head :: tail =>
          E.flatMap(engine.handleEvent(currentWf, head)) {
            case Some(nextWf) => loop(nextWf, tail)
            case None         => loop(currentWf, tail) // Silent fallback to match main branch
          }
      }
    }

    loop(initialState, events.toList)
  }

  // Extension to provide traverse_ for Option/List since we removed cats.syntax.all
  extension [A](opt: Option[A]) {
    def traverse_(f: A => F[Unit]): F[Unit] = opt match {
      case Some(a) => f(a)
      case None    => E.unit
    }
  }
}
