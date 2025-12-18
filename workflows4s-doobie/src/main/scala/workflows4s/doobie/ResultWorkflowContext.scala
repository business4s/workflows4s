package workflows4s.doobie

import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

/** Helper trait for Result-based workflow contexts. Extend this for workflows that use doobie's internal Result effect. This is primarily used for
  * testing doobie internals.
  */
trait ResultWorkflowContext extends WorkflowContext {
  type Eff[A] = Result[A]
  given effect: Effect[Eff] = resultEffect
}
