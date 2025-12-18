package workflows4s.doobie.testing

import cats.data.Kleisli
import doobie.ConnectionIO
import workflows4s.doobie.Result
import workflows4s.wio.*

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Random

/** Result-based test utilities for doobie runtimes.
  */
object ResultTestUtils {

  type Error = String

  def pure: (StepId, ResultTestCtx2.WIO[TestState, Nothing, TestState]) = {
    import ResultTestCtx2.*
    val stepId = StepId.random("pure")
    (stepId, WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }

  def error: (Error, ResultTestCtx2.WIO[Any, String, Nothing]) = {
    import ResultTestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    (error, WIO.pure.error(error).done)
  }

  def runIO: (StepId, ResultTestCtx2.WIO[TestState, Nothing, TestState]) = {
    runIOCustom(Kleisli.pure(()))
  }

  def errorIO: (Error, ResultTestCtx2.WIO[Any, String, Nothing]) = {
    import ResultTestCtx2.*
    val error = s"error-${UUID.randomUUID()}"
    case class RunIOErrored(error: String) extends ResultTestCtx2.Event
    val wio = WIO
      .runIO[Any](_ => Kleisli.pure[ConnectionIO, cats.effect.LiftIO[ConnectionIO], RunIOErrored](RunIOErrored(error)))
      .handleEventWithError((_, evt: RunIOErrored) => Left(evt.error))
      .done
    (error, wio)
  }

  def runIOCustom(logic: Result[Unit]): (StepId, ResultTestCtx2.WIO[TestState, Nothing, TestState]) = {
    import ResultTestCtx2.*
    case class RunIODone(stepId: StepId) extends ResultTestCtx2.Event
    val stepId = StepId.random
    val wio    = WIO
      .runIO[TestState](_ => logic.map(_ => RunIODone(stepId)))
      .handleEvent((st, evt: RunIODone) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def errorHandler: ResultTestCtx2.WIO[(TestState, Error), Nothing, TestState] = {
    import ResultTestCtx2.*
    WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).done
  }

  // inline assures two calls get different events
  inline def signal: (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Result, TestState, Nothing, TestState, ResultTestCtx2.Ctx]) =
    signalCustom(Kleisli.pure(()))

  // inline assures two calls get different events
  inline def signalCustom(
      logic: Result[Unit],
  ): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[Result, TestState, Nothing, TestState, ResultTestCtx2.Ctx]) = {
    import ResultTestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends ResultTestCtx2.Event with Serializable {
      override def toString: String = s"SigEvent(${req})"
    }
    val stepId = StepId.random("signal")
    val wio = WIO
      .handleSignal(signalDef)
      .using[TestState]
      .withSideEffects((_, req) => logic.map(_ => new SigEvent(req)))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt: SigEvent) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def signalError: (SignalDef[Int, Int], Error, WIO.IHandleSignal[Result, TestState, Error, TestState, ResultTestCtx2.Ctx]) = {
    import ResultTestCtx2.*
    val signalDef = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    case class SignalErrored(req: Int, error: String) extends ResultTestCtx2.Event
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

  def timer(
      secs: Int = Random.nextInt(10) + 1,
  ): (FiniteDuration, WIO.Timer[ResultTestCtx2.Eff, ResultTestCtx2.Ctx, TestState, Nothing, TestState]) = {
    import ResultTestCtx2.*
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
