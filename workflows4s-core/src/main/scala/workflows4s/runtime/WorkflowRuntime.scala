package workflows4s.runtime

import workflows4s.wio.{WCState, WIO, WorkflowContext}

trait WorkflowRuntime[F[_], Ctx <: WorkflowContext, WorkflowId, Input] {

  def createInstance(id: WorkflowId, input: Input): F[WorkflowInstance[F, WCState[Ctx]]]

}
