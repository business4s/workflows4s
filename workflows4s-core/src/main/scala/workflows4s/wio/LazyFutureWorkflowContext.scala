package workflows4s.wio

import scala.concurrent.ExecutionContext
import workflows4s.runtime.instanceengine.{Effect, LazyFuture}

/** Helper trait for LazyFuture-based workflow contexts. Extend this for workflows that use LazyFuture.
  *
  * Override `executionContext` to provide a custom ExecutionContext, or use the default global one.
  */
trait LazyFutureWorkflowContext extends WorkflowContext {
  type Eff[A] = LazyFuture[A]

  /** Override this to provide a custom ExecutionContext. Defaults to the global ExecutionContext.
    */
  def executionContext: ExecutionContext = ExecutionContext.global

  implicit def effect: Effect[Eff] = LazyFuture.lazyFutureEffect(using executionContext)
}
