package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId

object WorkflowRegistry {

  enum ExecutionStatus {
    case Running, Awaiting, Finished
  }

  trait Agent {

    def upsertInstance(id: WorkflowInstanceId, executionStatus: ExecutionStatus): IO[Unit]
    
  }

}
