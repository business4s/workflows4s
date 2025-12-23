package workflows4s.testing

import cats.effect.IO
import workflows4s.wio.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/** IO-based test utilities for runtimes that use IO effect type (Pekko, Doobie).
  *
  * Note: These utilities are intentionally IO-specific (not effect-polymorphic) because they're used by IOTestRuntimeAdapter which tests concrete
  * IO-based runtime implementations. If similar utilities are needed for other effect types, consider creating effect-polymorphic versions in
  * workflows4s-core/test.
  */
object IOTestUtils {

  type Error = String

  def pure: (StepId, IOTestCtx2.WIO[TestState, Nothing, TestState]) = {
    import IOTestCtx2.*
    val stepId = StepId.random("pure")
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }

  def error: (Error, IOTestCtx2.WIO[Any, String, Nothing]) = {
    import IOTestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }

  def runIO: (StepId, IOTestCtx2.WIO[TestState, Nothing, TestState]) = {
    runIOCustom(IO.unit)
  }

  def errorIO: (Error, IOTestCtx2.WIO[Any, String, Nothing]) = {
    import IOTestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunIOErrored(error: String) extends IOTestCtx2.Event
    val wio = WIO
      .runIO[Any](_ => IO.pure(RunIOErrored(error)))
      .handleEventWithError((_, evt: RunIOErrored) => Left(evt.error))
      .done
    (error, wio)
  }

  def runIOCustom(logic: IO[Unit]): (StepId, IOTestCtx2.WIO[TestState, Nothing, TestState]) = {
    import IOTestCtx2.*
    case class RunIODone(stepId: StepId) extends IOTestCtx2.Event
    val stepId = StepId.random
    val wio    = WIO
      .runIO[TestState](_ => logic.as(RunIODone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def errorHandler: IOTestCtx2.WIO[(TestState, Error), Nothing, TestState] = {
    import IOTestCtx2.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  // inline assures two calls get different events
  inline def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[IO, TestState, Nothing, TestState, IOTestCtx2.Ctx]) = signalCustom(IO.unit)

  // inline assures two calls get different events
  inline def signalCustom(logic: IO[Unit]): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[IO, TestState, Nothing, TestState, IOTestCtx2.Ctx]) = {
    import IOTestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends IOTestCtx2.Event with Serializable {
      override def toString: String = s"SigEvent(${req})"
    }
    val stepId = StepId.random("signal")
    val wio = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .withSideEffects((_, req) => logic.as(SigEvent(req)))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[IO, TestState, Error, TestState, IOTestCtx2.Ctx]) = {
    import IOTestCtx2.{*, given}
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    case class SignalErrored(req: Int, error: String) extends IOTestCtx2.Event
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

  def timer(secs: Int = Random.nextInt(10) + 1): (FiniteDuration, WIO.Timer[IOTestCtx2.Eff, IOTestCtx2.Ctx, TestState, Nothing, TestState]) = {
    import IOTestCtx2.*
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
