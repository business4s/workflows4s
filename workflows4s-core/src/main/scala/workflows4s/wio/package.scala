package workflows4s

package object wio {

  type WCState[T <: WorkflowContext]  = WorkflowContext.State[T]
  type WCEvent[T <: WorkflowContext]  = WorkflowContext.Event[T]
  type WCEffect[T <: WorkflowContext] = WorkflowContext.Effect[T]

  /** Natural transformation from the workflow effect to `F`. */
  type WCEffectLift[Ctx <: WorkflowContext, F[_]] = [A] => WCEffect[Ctx][A] => F[A]

}
