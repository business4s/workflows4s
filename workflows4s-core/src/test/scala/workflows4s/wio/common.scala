package workflows4s.wio

import cats.Id
import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect

object TestCtx extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = String

  // Use Id as the effect type for tests
  type Eff[A] = Id[A]
  implicit val effect: Effect[Eff] = Effect.idEffect

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Eff, Ctx] =
      ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???

  given Conversion[String, SimpleEvent] = SimpleEvent.apply
}

opaque type StepId <: String = String
object StepId {
  def random: StepId                 = scala.util.Random.alphanumeric.take(8).mkString
  def random(prefix: String): StepId = s"$prefix-$random"
  extension (s: StepId) {
    def prefixedWith(prefix: String): StepId = s"${prefix}:${s}"
  }
}

case class TestState(executed: List[StepId], errors: List[String] = List()) {
  def addExecuted(id: StepId): TestState = this.copy(executed = this.executed.appended(id))
  def addError(err: String): TestState   = this.copy(errors = this.errors.appended(err))

  def ++(other: TestState): TestState = TestState(this.executed ++ other.executed, this.errors ++ other.errors)

  def prefixWith(prefix: String): TestState = TestState(this.executed.map(_.prefixedWith(prefix)), this.errors.map(x => s"$prefix:$x"))
}

object TestState {
  def empty = TestState(List(), List())
}

object TestCtx2 extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = TestState

  // Use Id as the effect type for tests
  type Eff[A] = Id[A]
  implicit val effect: Effect[Eff] = Effect.idEffect
}

// IO-based version of TestCtx2 for InMemoryRuntime tests
object IOTestCtx2 extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = TestState

  type Eff[A] = IO[A]
  implicit val effect: Effect[Eff] = Effect.ioEffect
}

// IO-based context for InMemoryRuntime tests
object IOTestCtx extends WorkflowContext {
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = String

  type Eff[A] = IO[A]
  implicit val effect: Effect[Eff] = Effect.ioEffect

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Eff, Ctx] =
      ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???

  given Conversion[String, SimpleEvent] = SimpleEvent.apply
}
