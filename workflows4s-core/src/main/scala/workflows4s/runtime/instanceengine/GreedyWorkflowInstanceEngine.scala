package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

class GreedyWorkflowInstanceEngine(protected val delegate: WorkflowInstanceEngine)
    extends DelegatingWorkflowInstanceEngine {

  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): IO[Set[PostExecCommand]] = {
    super.onStateChange(oldState, newState).map(_ + PostExecCommand.WakeUp)
  }

}
