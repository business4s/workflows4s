package workflows4s.runtime

import scala.concurrent.ExecutionContext.Implicits.global
import workflows4s.runtime.instanceengine.{Effect, LazyFuture}
import workflows4s.wio.{LazyFutureWorkflowContext, WCState, WIO}

class InMemoryRuntimeLazyFutureTest extends InMemoryRuntimeTestSuite[LazyFuture] {

  given effect: Effect[LazyFuture] = LazyFuture.lazyFutureEffect

  object TestCtx extends LazyFutureWorkflowContext {
    trait Event
    type State = String
  }

  override type Ctx = TestCtx.Ctx

  override def simpleWorkflow: WIO.Initial[LazyFuture, Ctx] = {
    import TestCtx.WIO
    WIO.pure("myValue").done
  }

  override def initialState: WCState[Ctx] = "initialState"

  "InMemoryRuntime (LazyFuture)" - {
    inMemoryRuntimeTests()
  }
}
