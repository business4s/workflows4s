package workflows4s.wio

import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.internal.{SignalResult, WakeupResult}

import java.time.Instant

given WeakSync[IO] with {
  override def delay[A](body: => A): IO[A] = IO(body)
}

extension [Ctx <: WorkflowContext](wf: ActiveWorkflow[Ctx])(using lift: LiftWorkflowEffect[Ctx, IO]) {
  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResult[IO, WCEvent[Ctx], Resp] =
    wf.handleSignal(signalDef, req, lift.asPoly)

  def proceed(now: Instant): WakeupResult[IO, WCEvent[Ctx]] =
    wf.proceed(now, lift.asPoly)
}

object TestCtx extends WorkflowContext {
  type Effect[T] = IO[T]
  trait Event
  case class SimpleEvent(value: String) extends Event
  type State = String

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Ctx] =
      ActiveWorkflow(WorkflowInstanceId("test", "test"), wio.provideInput(state), state)
  }

  def ignore[A, B, C]: (A, B) => C        = (_, _) => ???
  def ignore3[A, B, C, D]: (A, B, C) => D = (_, _, _) => ???

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
  type Effect[T] = IO[T]
  trait Event
  case class TimerStarted(inner: WIO.Timer.Started)   extends Event
  case class TimerReleased(inner: WIO.Timer.Released) extends Event
  case class SimpleEvent(value: String)               extends Event
  type State = TestState
}
