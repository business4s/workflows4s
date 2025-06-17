package workflows4s.testing

import cats.effect.IO
import workflows4s.runtime.registry.NoOpWorkflowRegistry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.{InMemorySyncRuntime, InMemorySyncWorkflowInstance}
import workflows4s.wio.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

class TestRuntime {
  val clock        = TestClock()
  val knockerUpper = FakeKnockerUpper[Unit]()

  def createInstance(wio: WIO[TestState, Nothing, TestState, TestCtx2.Ctx]): InMemorySyncWorkflowInstance[TestCtx2.Ctx] = {
    import cats.effect.unsafe.implicits.global
    val instance: InMemorySyncWorkflowInstance[TestCtx2.Ctx] =
      new InMemorySyncRuntime[TestCtx2.Ctx, Unit](
        wio.provideInput(TestState.empty),
        TestState.empty,
        clock,
        knockerUpper,
        NoOpWorkflowRegistry.Agent,
      )
        .createInstance(())
    instance
  }
}

object TestUtils {

  type Error = String

  def createInstance2(wio: WIO[TestState, Nothing, TestState, TestCtx2.Ctx]): (TestClock, InMemorySyncWorkflowInstance[TestCtx2.Ctx]) = {
    val clock                                                = new TestClock()
    import cats.effect.unsafe.implicits.global
    val instance: InMemorySyncWorkflowInstance[TestCtx2.Ctx] =
      new InMemorySyncRuntime[TestCtx2.Ctx, Unit](
        wio.provideInput(TestState.empty),
        TestState.empty,
        clock,
        NoOpKnockerUpper.Agent,
        NoOpWorkflowRegistry.Agent,
      )
        .createInstance(())
    (clock, instance)
  }

  def pure: (StepId, WIO[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val stepId = StepId.random
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }
  def error: (Error, WIO[Any, String, Nothing, TestCtx2.Ctx])         = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }

  def runIO: (StepId, WIO[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    runIOCustom(IO.unit)
  }

  def errorIO: (Error, WIO[Any, String, Nothing, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunIOErrored(error: String) extends TestCtx2.Event
    val wio = WIO
      .runIO[Any](_ => IO.pure(RunIOErrored(error)))
      .handleEventWithError((_, evt) => Left(evt.error))
      .done
    (error, wio)
  }

  def runIOCustom(logic: IO[Unit]): (StepId, WIO[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    case class RunIODone(stepId: StepId) extends TestCtx2.Event
    val stepId = StepId.random
    val wio    = WIO
      .runIO[TestState](_ => logic.as(RunIODone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def errorHandler: WIO[(TestState, Error), Nothing, TestState, TestCtx2.Ctx] = {
    import TestCtx2.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  // inline assures two calls get different events
  inline def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends TestCtx2.Event with Serializable
    val stepId = StepId.random
    val wio    = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => SigEvent(req))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[TestState, Error, TestState, TestCtx2.Ctx]) = {
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

  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO.Timer[TestCtx2.Ctx, TestState, Nothing, TestState]) = {
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
