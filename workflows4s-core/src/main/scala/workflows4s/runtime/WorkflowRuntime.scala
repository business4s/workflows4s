package workflows4s.runtime

import workflows4s.wio.{WCState, WIO, WorkflowContext}

/** A runtime for executing workflows.
  *
  * @tparam F
  *   Effect type (e.g., IO, Id)
  * @tparam Ctx
  *   The workflow context type
  */
trait WorkflowRuntime[F[_], Ctx <: WorkflowContext] {

  def templateId: String

  def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]]

  def workflow: WIO.Initial[F, Ctx]

}
