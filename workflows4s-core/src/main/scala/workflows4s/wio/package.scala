package workflows4s

package object wio {

  type WCState[T <: WorkflowContext] = WorkflowContext.State[T]
  type WCEvent[T <: WorkflowContext] = WorkflowContext.Event[T]

}
