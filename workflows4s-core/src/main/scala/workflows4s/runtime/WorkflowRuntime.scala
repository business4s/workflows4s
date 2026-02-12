package workflows4s.runtime

import workflows4s.wio.{WCState, WIO, WorkflowContext}

/** Factory for creating [[WorkflowInstance]]s from a workflow definition.
  *
  * Each runtime is tied to a single workflow definition (the `workflow` value) and a `templateId`. Implementations differ in how they persist state:
  * in-memory, database-backed (doobie), or actor-based (Pekko).
  */
trait WorkflowRuntime[F[_], Ctx <: WorkflowContext] {

  /** Stable identifier for this workflow definition. */
  def templateId: String

  /** Creates (or recovers) a workflow instance with the given id. If events exist for this id, the instance is recovered from them. */
  def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]]

  def workflow: WIO.Initial[Ctx]

}
