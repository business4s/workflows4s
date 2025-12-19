package workflows4s.wio

import cats.effect.IO
import workflows4s.cats.CatsEffect
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect

// IO-based version of TestCtx2 for InMemoryRuntime tests
object IOTestCtx2 extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = TestState

  type Eff[A] = IO[A]
  given effect: Effect[Eff] = CatsEffect.ioEffect
}

// IO-based context for InMemoryRuntime tests
object IOTestCtx extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = String

  type Eff[A] = IO[A]
  given effect: Effect[Eff] = CatsEffect.ioEffect

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Eff, Ctx] =
      ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???

  given Conversion[String, SimpleEvent] = SimpleEvent.apply
}
