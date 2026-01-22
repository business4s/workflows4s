package workflows4s.wio.linter

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.{Linter, TestCtx2, TestState, WIO}

class ClashingEventsRuleTest extends AnyFreeSpec with Matchers {

  "ClashingEventsRule" - {
    "should detect clashing events in parallel" in {
      val recover = TestCtx2.WIO.recover((st: TestState, _: TestCtx2.SimpleEvent) => st)
      val wf      = TestCtx2.WIO.parallel
        .taking[TestState]
        .withInterimState[TestState](in => in)
        .withElement(recover, _ ++ _)
        .withElement(recover, _ ++ _)
        .producingOutputWith((a, b) => a ++ b)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(
        s"Clashing event: SimpleEvent expected in parallel branches 0 and 1. This will lead to non-deterministic recovery.",
      )
    }

    "should detect clashing events in interruption" in {
      val recover      = TestCtx2.WIO.recover((st: TestState, _: TestCtx2.SimpleEvent) => st)
      val interruption = WIO.Interruption[TestCtx2.Ctx, Nothing, TestState](recover, WIO.HandleInterruption.InterruptionType.Signal)
      val wf           = recover.interruptWith(interruption)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(
        s"Clashing event: SimpleEvent expected in both base and interruption trigger. This will lead to non-deterministic recovery.",
      )
    }

    "should detect clashing timers in parallel" in {
      val timer = TestCtx2.WIO
        .await[TestState](java.time.Duration.ofSeconds(1))
        .persistStartThrough(x => TestCtx2.TimerStarted(x))(_.inner.at)
        .persistReleaseThrough(x => TestCtx2.TimerReleased(x))(_.inner.at)
        .done
      val wf    = TestCtx2.WIO.parallel
        .taking[TestState]
        .withInterimState[TestState](in => in)
        .withElement(timer, _ ++ _)
        .withElement(timer, _ ++ _)
        .producingOutputWith((a, b) => a ++ b)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(
        s"Clashing event: TimerStarted expected in parallel branches 0 and 1. This will lead to non-deterministic recovery.",
      )
    }

    "should detect clashing events in checkpoint" in {
      val recover = TestCtx2.WIO.recover((st: TestState, _: TestCtx2.SimpleEvent) => st)
      val wf      = recover.checkpointed(
        (_, _) => TestCtx2.SimpleEvent("checkpoint"),
        (in, _) => in,
      )

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(
        s"Clashing event: SimpleEvent expected in both base and checkpoint. This will lead to non-deterministic recovery.",
      )
    }
  }

}
