package workflows4s.runtime

import workflows4s.wio.{WCState, WIO, WorkflowContext}

/** Factory for creating [[WorkflowInstance]]s from a workflow definition.
  *
  * Each runtime is tied to a single workflow definition (the `workflow` value) and a `templateId`. Implementations differ in how they persist state:
  * in-memory, database-backed (doobie), or actor-based (Pekko).
  *
  * @tparam F
  *   the runtime effect type
  * @tparam Ctx
  *   the workflow context
  */
trait WorkflowRuntime[F[_], Ctx <: WorkflowContext] {

  /** The effect type used by the workflow/engine. May differ from F (e.g. IO workflow run via Future runtime). */
  type WorkflowEffect[_]

  /** Stable identifier for this workflow definition. */
  def templateId: String

  /** Creates (or recovers) a workflow instance with the given id. If events exist for this id, the instance is recovered from them. */
  def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]]

  def workflow: WIO.Initial[WorkflowEffect, Ctx]

}
