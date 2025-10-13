package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.ActiveWorkflow

object NoOpWorkflowRegistry {

  object Agent extends WorkflowRegistry.Agent {
    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit] = IO.unit
  }

}
