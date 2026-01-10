package workflows4s.cats

import cats.effect.IO
import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.WorkflowContext

/** Helper trait for IO-based workflow contexts. Extend this for workflows that use cats.effect.IO. Uses the global IORuntime by default. Override the
  * effect given to use a custom runtime.
  */
trait IOWorkflowContext extends WorkflowContext {
  type Eff[A] = IO[A]
  given effect: Effect[Eff] = CatsEffect.globalIoEffect
}
