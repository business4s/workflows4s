package workflows4s.runtime.registry

import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow

/** Tracks running workflow instances and their execution status. Used by the web UI for listing/searching workflows. */
object WorkflowRegistry {

  enum ExecutionStatus {
    case Running, Awaiting, Finished
  }

  /** Called by the engine after each state change to update the registry. */
  trait Agent {

    def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit]

  }

  /** Extracts user-defined tags from workflow state for filtering/searching. */
  trait Tagger[State] {
    def getTags(id: WorkflowInstanceId, state: State): Map[String, String]
  }

}
