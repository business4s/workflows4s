package workflow4s.runtime

import workflow4s.wio.{WCState, WorkflowContext}

trait WorkflowRuntime[F[_], Ctx <: WorkflowContext, WorkflowId, Input] {

  def createInstance(id: WorkflowId, input: Input): F[WorkflowInstance[F, WCState[Ctx]]]

}
