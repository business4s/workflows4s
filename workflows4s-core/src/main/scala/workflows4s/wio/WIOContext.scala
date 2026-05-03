package workflows4s.wio

import workflows4s.runtime.WorkflowInstanceId

/** Runtime context not specific to the current step.
  */
final case class WIOContext[+State](instanceId: WorkflowInstanceId, currentState: State)

object WIOContext {
  def instanceId(using ctx: WIOContext[?]): WorkflowInstanceId = ctx.instanceId
  def currentState[S](using ctx: WIOContext[S]): S             = ctx.currentState
}
