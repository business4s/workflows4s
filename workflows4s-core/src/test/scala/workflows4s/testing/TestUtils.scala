package workflows4s.testing

import cats.Id
import cats.effect.IO
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.{InMemoryRuntime, InMemoryWorkflowInstance, WorkflowInstanceId}
import workflows4s.wio.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class TestRuntime {
  val clock        = TestClock()
  given Effect[Id] = Effect.idEffect
  val knockerUpper = IdRecordingKnockerUpper()

  val engine: WorkflowInstanceEngine[Id] =
    WorkflowInstanceEngine
      .builder[Id]
      .withJavaTime(clock)
      .withWakeUps(knockerUpper)
      .withoutRegistering
      .withGreedyEvaluation
      .withLogging
      .get

  def createInstance(wio: TestCtx2.WIO[TestState, Nothing, TestState]): InMemoryWorkflowInstance[Id, TestCtx2.Ctx] = {
    val runtime = InMemoryRuntime.create[Id, TestCtx2.Ctx](
      wio.provideInput(TestState.empty),
      TestState.empty,
      engine,
      "test",
    )
    runtime.createInstance(UUID.randomUUID().toString).asInstanceOf[InMemoryWorkflowInstance[Id, TestCtx2.Ctx]]
  }
}

object TestUtils {

  def randomWfId() = WorkflowInstanceId(UUID.randomUUID().toString.take(4), UUID.randomUUID().toString.take(4))

  type Error = String

  def createInstance2(wio: TestCtx2.WIO[TestState, Nothing, TestState]): (TestClock, InMemoryWorkflowInstance[Id, TestCtx2.Ctx]) = {
    val clock        = new TestClock()
    given Effect[Id] = Effect.idEffect
    val runtime      = InMemoryRuntime.create[Id, TestCtx2.Ctx](
      wio.provideInput(TestState.empty),
      TestState.empty,
      WorkflowInstanceEngine.basic[Id](clock),
      "test",
    )
    (clock, runtime.createInstance(UUID.randomUUID().toString).asInstanceOf[InMemoryWorkflowInstance[Id, TestCtx2.Ctx]])
  }

  def pure: (StepId, TestCtx2.WIO[TestState, Nothing, TestState]) = {
    import TestCtx2.*
    val stepId = StepId.random("pure")
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }
  def error: (Error, TestCtx2.WIO[Any, String, Nothing])          = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }

  def runIO: (StepId, TestCtx2.WIO[TestState, Nothing, TestState]) = {
    runIOCustom(IO.unit)
  }

  def errorIO: (Error, TestCtx2.WIO[Any, String, Nothing]) = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunIOErrored(error: String) extends TestCtx2.Event
    val wio = WIO
      .runIO[Any](_ => RunIOErrored(error))
      .handleEventWithError((_, evt: RunIOErrored) => Left(evt.error))
      .done
    (error, wio)
  }

  def runIOCustom(logic: IO[Unit]): (StepId, TestCtx2.WIO[TestState, Nothing, TestState]) = {
    import TestCtx2.*
    case class RunIODone(stepId: StepId) extends TestCtx2.Event
    val stepId = StepId.random
    // For Id effect, we need to run the IO synchronously
    val wio    = WIO
      .runIO[TestState](_ => {
        import cats.effect.unsafe.implicits.global
        logic.unsafeRunSync()
        RunIODone(stepId)
      })
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def errorHandler: TestCtx2.WIO[(TestState, Error), Nothing, TestState] = {
    import TestCtx2.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  // inline assures two calls get different events
  inline def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Id, TestState, Nothing, TestState, TestCtx2.Ctx]) = signalCustom(IO.unit)

  // inline assures two calls get different events
  inline def signalCustom(logic: IO[Unit]): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Id, TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends TestCtx2.Event with Serializable {
      override def toString: String = s"SigEvent(${req})"
    }
    val stepId = StepId.random("signal")
    // For Id effect, we need to run the IO synchronously
    val wio = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .withSideEffects((_, req) => {
        import cats.effect.unsafe.implicits.global
        logic.unsafeRunSync()
        SigEvent(req)
      })
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[Id, TestState, Error, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    case class SignalErrored(req: Int, error: String) extends TestCtx2.Event
    val error = s"error-${UUID.randomUUID()}"
    val wio   = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => SignalErrored(req, error))
      .handleEventWithError((_, evt) => Left(evt.error))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, error, wio)
  }

  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO.Timer[TestCtx2.Eff, TestCtx2.Ctx, TestState, Nothing, TestState]) = {
    import TestCtx2.*
    case class Started(instant: Instant)  extends Event
    case class Released(instant: Instant) extends Event
    val duration = secs.seconds
    val wio      = WIO
      .await[TestState](duration)
      .persistStartThrough(x => Started(x.at))(_.instant)
      .persistReleaseThrough(x => Released(x.at))(_.instant)
      .done
    (duration.plus(1.milli), wio)
  }
}
