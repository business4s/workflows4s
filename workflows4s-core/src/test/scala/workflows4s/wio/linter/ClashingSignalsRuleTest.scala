package workflows4s.wio.linter

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.{Linter, TestCtx2, TestState}

class ClashingSignalsRuleTest extends AnyFreeSpec with Matchers {

  "ClashingSignalsRule" - {
    "should detect clashing signals in parallel" in {
      val (signalDef, _, step) = TestUtils.signal
      val wf = TestCtx2.WIO.parallel
        .taking[TestState]
        .withInterimState[TestState](in => in)
        .withElement(step, _ ++ _)
        .withElement(step, _ ++ _)
        .producingOutputWith((a, b) => a ++ b)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(s"Clashing signal: ${signalDef.name} (${signalDef.id}) expected in parallel branches 0 and 1")
      issues.forall(_.ruleId == "clashing-signals")
    }

    "should detect clashing signals in interruption" in {
      val (signalDef, _, step) = TestUtils.signal
      val interruption = step.toInterruption
      val wf = step.interruptWith(interruption)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain(s"Clashing signal: ${signalDef.name} (${signalDef.id}) expected in both base and interruption trigger")
    }
  }

}
