package workflows4s.testing

import cats.effect.IO
import workflows4s.runtime.registry.NoOpWorkflowRegistry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.{InMemorySyncRuntime, InMemorySyncWorkflowInstance}
import workflows4s.wio.{TestCtx, TestCtx2, *}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

object TestUtils {

  def createInstance(wio: WIO.Initial[TestCtx.Ctx]): (TestClock, InMemorySyncWorkflowInstance[TestCtx.Ctx]) = {
    val clock                                               = new TestClock()
    import cats.effect.unsafe.implicits.global
    val instance: InMemorySyncWorkflowInstance[TestCtx.Ctx] =
      new InMemorySyncRuntime(wio, "initialState", clock, NoOpKnockerUpper.Agent, NoOpWorkflowRegistry.Agent).createInstance(())
    (clock, instance)
  }

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

  def pure: (StepId, WIO[TestState, Nothing, TestState, TestCtx2.Ctx])         = {
    import TestCtx2.*
    val stepId = StepId.random
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }
  def error: (String, WIO[Any, String, Nothing, TestCtx2.Ctx])                 = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }
  def errorIO: (String, WIO[Any, String, Nothing, TestCtx2.Ctx])               = {
    import TestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunIOErrored(error: String) extends TestCtx2.Event
    val wio = WIO
      .runIO[Any](_ => IO.pure(RunIOErrored(error)))
      .handleEventWithError((_, evt) => Left(evt.error))
      .done
    (error, wio)
  }
  def errorHandler: WIO[(TestState, String), Nothing, TestState, TestCtx2.Ctx] = {
    import TestCtx2.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    case class SigEvent(req: Int) extends TestCtx2.Event
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

  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
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

  def runIO: (StepId, WIO[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    import TestCtx2.*
    case class RunIODone(stepId: StepId) extends TestCtx2.Event
    val stepId = StepId.random
    val wio    = WIO
      .runIO[TestState](_ => IO.pure(RunIODone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

}
