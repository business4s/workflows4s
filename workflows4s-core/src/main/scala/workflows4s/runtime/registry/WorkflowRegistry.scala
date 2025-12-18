package workflows4s.runtime.registry

import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow

object WorkflowRegistry {

  enum ExecutionStatus {
    case Running, Awaiting, Finished
  }

  trait Agent[F[_]] {

    def upsertInstance(inst: ActiveWorkflow[?, ?], executionStatus: ExecutionStatus): F[Unit]

  }

  trait Tagger[State] {
    def getTags(id: WorkflowInstanceId, state: State): Map[String, String]
  }

}
