package workflows4s.runtime

import workflows4s.wio.{WCState, WorkflowContext}

trait WorkflowRuntime[F[_], Ctx <: WorkflowContext, WorkflowId] {

  def createInstance(id: WorkflowId): F[WorkflowInstance[F, WCState[Ctx]]]

}
