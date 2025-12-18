package workflows4s.wio

import workflows4s.runtime.instanceengine.Effect

object DraftWorkflowContext extends WorkflowContext {
  // DraftWorkflowContext uses Id as its effect type since it's for draft/design purposes
  type Eff[A] = A
  given effect: Effect[Eff] = Effect.idEffect
}
