package workflows4s.runtime.instanceengine

import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

class GreedyWorkflowInstanceEngine[F[_]](protected val delegate: WorkflowInstanceEngine[F])(using Effect[F])
    extends DelegatingWorkflowInstanceEngine[F] {
  import Effect.*
  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[F, Ctx], newState: ActiveWorkflow[F, Ctx]): F[Set[PostExecCommand]] = {
    super.onStateChange(oldState, newState).map(_ + PostExecCommand.WakeUp)
  }

}
