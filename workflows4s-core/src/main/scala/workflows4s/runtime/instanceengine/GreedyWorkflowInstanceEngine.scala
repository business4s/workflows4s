package workflows4s.runtime.instanceengine

import cats.Functor
import cats.syntax.functor.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

class GreedyWorkflowInstanceEngine[F[_]: Functor](protected val delegate: WorkflowInstanceEngine[F]) extends DelegatingWorkflowInstanceEngine[F] {

  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): F[Set[PostExecCommand]] = {
    super.onStateChange(oldState, newState).map(_ + PostExecCommand.WakeUp)
  }

}
