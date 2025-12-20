package workflows4s.runtime.instanceengine

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.{ActiveWorkflow, WCEvent, WorkflowContext}

import java.time.Instant

class WakingWorkflowInstanceEngine(protected val delegate: WorkflowInstanceEngine, knockerUpper: KnockerUpper.Agent)
    extends DelegatingWorkflowInstanceEngine
    with StrictLogging {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WakeupResult[WCEvent[Ctx]]] =
    super
      .triggerWakeup(workflow)
      .map({
        case WakeupResult.Noop               => WakeupResult.Noop
        case WakeupResult.Processed(eventIO) =>
          WakeupResult.Processed(for {
            result <- eventIO
            _      <- result match {
                        case ProcessingResult.Proceeded(event)  => IO.unit
                        case ProcessingResult.Failed(retryTime) =>
                          if workflow.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(workflow, Some(retryTime))
                          else IO.unit
                      }
          } yield result)
      })
  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[WorkflowInstanceEngine.PostExecCommand]]                                                                = {
    super.onStateChange(oldState, newState) <*
      IO.whenA(newState.wakeupAt != oldState.wakeupAt)(updateWakeup(newState, newState.wakeupAt))
  }

  private def updateWakeup(workflow: ActiveWorkflow[?], time: Option[Instant]) = {
    IO(logger.debug(s"Registering wakeup for ${workflow.id} at $time")).void *>
      knockerUpper
        .updateWakeup(workflow.id, time)
        .handleError(err => {
          logger.error("Failed to register wakeup", err)
        })
  }

}
