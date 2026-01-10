package workflows4s.runtime.registry

import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.ActiveWorkflow

object NoOpWorkflowRegistry {

  def agent[F[_]](using E: Effect[F]): WorkflowRegistry.Agent[F] =
    new WorkflowRegistry.Agent[F] {
      override def upsertInstance(inst: ActiveWorkflow[F, ?], executionStatus: ExecutionStatus): F[Unit] = E.unit
    }

}
