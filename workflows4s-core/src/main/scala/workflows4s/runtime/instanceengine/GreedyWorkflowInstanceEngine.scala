package workflows4s.runtime.instanceengine

import cats.Functor
import cats.syntax.functor.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

class GreedyWorkflowInstanceEngine[F[_]: Functor, Ctx <: WorkflowContext](protected val delegate: WorkflowInstanceEngine[F, Ctx])
    extends DelegatingWorkflowInstanceEngine[F, Ctx] {

  override def onStateChange(oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): F[Set[PostExecCommand]] = {
    super.onStateChange(oldState, newState).map(_ + PostExecCommand.WakeUp)
  }

}
