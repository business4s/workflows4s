package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus

object NoOpWorkflowRegistry {

  object Agent extends WorkflowRegistry.Agent {
    override def upsertInstance(id: WorkflowInstanceId, executionStatus: ExecutionStatus): IO[Unit] = IO.unit
  }

}
