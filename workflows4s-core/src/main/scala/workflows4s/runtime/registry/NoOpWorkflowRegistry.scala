package workflows4s.runtime.registry

import workflows4s.effect.Effect
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.ActiveWorkflow

object NoOpWorkflowRegistry {

  def agent[F[_]: Effect]: WorkflowRegistry.Agent[F] = new WorkflowRegistry.Agent[F] {
    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): F[Unit] = Effect[F].unit
  }

}
