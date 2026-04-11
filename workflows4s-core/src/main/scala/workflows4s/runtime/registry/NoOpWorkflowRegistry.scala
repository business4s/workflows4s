package workflows4s.runtime.registry

import cats.Applicative
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.ActiveWorkflow

object NoOpWorkflowRegistry {

  def agent[F[_]: Applicative]: WorkflowRegistry.Agent[F] = new WorkflowRegistry.Agent[F] {
    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): F[Unit] = Applicative[F].unit
  }

}
