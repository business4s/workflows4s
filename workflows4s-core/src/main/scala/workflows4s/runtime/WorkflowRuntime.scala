package workflows4s.runtime

import workflows4s.wio.{WCState, WorkflowContext}

trait WorkflowRuntime[F[_], Ctx <: WorkflowContext] {

  def templateId: String
  def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]]

}
