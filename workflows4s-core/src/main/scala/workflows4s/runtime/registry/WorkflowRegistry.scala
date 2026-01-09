package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow

object WorkflowRegistry {

  enum ExecutionStatus {
    case Running
    case AwaitingTrigger
    case AwaitingNextStep
    case Finished
  }

  trait Agent {

    def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit]

  }

  trait Tagger[State] {
    def getTags(id: WorkflowInstanceId, state: State): Map[String, String]
  }

}
