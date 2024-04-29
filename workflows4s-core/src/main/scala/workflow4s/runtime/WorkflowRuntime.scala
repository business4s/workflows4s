package workflow4s.runtime

import workflow4s.wio.{WCState, WIO, WorkflowContext}

trait WorkflowRuntime[F[_]] {
  type WorkflowId

  def createInstance[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      id: WorkflowId,
      workflow: WIO.Initial[Ctx, In],
      initialState: In,
  ): RunningWorkflow[F, WCState[Ctx]]

}
