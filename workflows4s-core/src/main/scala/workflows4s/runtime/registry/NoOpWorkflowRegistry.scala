package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus

object NoOpWorkflowRegistry {

  object Agent extends WorkflowRegistry.Agent[Any] {
    override def upsertInstance(id: Any, executionStatus: ExecutionStatus): IO[Unit] = IO.unit
  }

}
