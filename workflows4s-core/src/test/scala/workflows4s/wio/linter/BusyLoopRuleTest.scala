package workflows4s.wio.linter

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.{Linter, TestCtx2, TestState}

class BusyLoopRuleTest extends AnyFreeSpec with Matchers {

  "BusyLoopRule" - {
    "should detect busy loop" in {
      val pure = TestUtils.pure._2
      val wf   = TestCtx2.WIO
        .repeat(pure)
        .until((_: TestState) => true)
        .onRestart(pure)
        .done

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain("Busy loop detected: loop body doesn't contain any signals or timers")
    }

    "should not detect busy loop when signal is present" in {
      val (_, _, step) = TestUtils.signal
      val pure         = TestUtils.pure._2
      val wf           = TestCtx2.WIO
        .repeat(step)
        .until((_: TestState) => true)
        .onRestart(pure)
        .done

      val issues = Linter.lint(wf)
      assert(!issues.map(_.message).exists(_.contains("Busy loop")))
    }

    "should not detect busy loop when timer is present" in {
      val timer = TestCtx2.WIO
        .await[TestState](java.time.Duration.ofSeconds(1))
        .persistStartThrough(x => TestCtx2.TimerStarted(x))(_.inner.at)
        .persistReleaseThrough(x => TestCtx2.TimerReleased(x))(_.inner.at)
        .done
      val pure  = TestUtils.pure._2
      val wf    = TestCtx2.WIO
        .repeat(timer)
        .until((_: TestState) => true)
        .onRestart(pure)
        .done

      val issues = Linter.lint(wf)
      assert(!issues.map(_.message).exists(_.contains("Busy loop")))
    }
  }

}
