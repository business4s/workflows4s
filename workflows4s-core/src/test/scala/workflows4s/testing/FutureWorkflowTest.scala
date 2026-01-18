package workflows4s.testing

import workflows4s.runtime.instanceengine.{Effect, LazyFuture}

// Ensure an ExecutionContext is available (global is standard for tests)
import scala.concurrent.ExecutionContext.Implicits.global

class FutureWorkflowTest extends WorkflowRuntimeTest[LazyFuture] {

  override given effect: Effect[LazyFuture] = LazyFuture.lazyFutureEffect

  override def unsafeRun(program: => LazyFuture[Unit]): Unit =
    effect.runSyncUnsafe(program)

  def getAdapter: Adapter = new WorkflowTestAdapter.InMemory[LazyFuture, ctx.type]()

  workflowTests(getAdapter)
}
