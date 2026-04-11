package workflows4s.testing.matrix

import cats.Applicative
import workflows4s.wio.*

import java.util.UUID
import scala.concurrent.duration.*

/** Workflow building blocks for matrix testing. Defines workflow structure once, parameterized by context type.
  *
  * The `effectToWCEffect` bridge in WorkflowContext handles the Effect[A] -> WCEffect[Ctx][A] conversion.
  */
object TestWorkflows {

  def pure(ctx: TestContextBase): (StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    val stepId = StepId.random("pure")
    (stepId, ctx.WIO.pure.makeFrom[TestState].value(_.addExecuted(stepId)).done)
  }

  def runIO(ctx: TestContextBase): (StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    val app    = ctx.effectApplicative
    val stepId = StepId.random("io")
    val wio    = ctx.WIO
      .runIO[TestState](_ => app.pure(TestEvent.RunIODone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  def signal(ctx: TestContextBase): (SignalDef[Int, Int], StepId, ctx.WIO[TestState, Nothing, TestState]) = {
    given Applicative[WCEffect[ctx.Ctx]] = ctx.wcEffectApplicative(using ctx.effectApplicative)
    val signalDef                        = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    val stepId                           = StepId.random("signal")
    val wio                              = ctx.WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => TestEvent.SigDone(req))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt, _) => evt.req)
      .done
    (signalDef, stepId, wio)
  }

  def timer(ctx: TestContextBase)(secs: Int = 5): (FiniteDuration, ctx.WIO[TestState, Nothing, TestState]) = {
    val duration = secs.seconds
    val wio      = ctx.WIO
      .await[TestState](duration)
      .persistStartThrough(x => TestEvent.TimerStarted(x.at))(_.at)
      .persistReleaseThrough(x => TestEvent.TimerReleased(x.at))(_.at)
      .done
    (duration.plus(1.milli), wio)
  }
}
