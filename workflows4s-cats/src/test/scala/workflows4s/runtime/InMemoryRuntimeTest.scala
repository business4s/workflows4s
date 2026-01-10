package workflows4s.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.cats.{CatsEffect, IOWorkflowContext}
import workflows4s.runtime.instanceengine.Effect
import workflows4s.wio.{WCState, WIO}

class InMemoryRuntimeTest extends InMemoryRuntimeTestSuite[IO] {

  given effect: Effect[IO] = CatsEffect.ioEffect

  object TestCtx extends IOWorkflowContext {
    trait Event
    type State = String
  }

  override type Ctx = TestCtx.Ctx

  override def simpleWorkflow: WIO.Initial[IO, Ctx] = {
    import TestCtx.WIO
    WIO.pure("myValue").done
  }

  override def initialState: WCState[Ctx] = "initialState"

  "InMemoryRuntime (IO)" - {
    inMemoryRuntimeTests()
  }
}
