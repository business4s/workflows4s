package workflows4s.wio.linter

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.Linter

class UnnecessaryErrorHandlerRuleTest extends AnyFreeSpec with Matchers {

  "UnnecessaryErrorHandlerRule" - {
    "should detect unnecessary error handler" in {
      val wf = TestUtils.pure._2
        .handleErrorWith(TestUtils.errorHandler)

      val issues = Linter.lint(wf)
      issues.map(_.message) should contain("Unnecessary error handler: base workflow cannot fail")
    }

    "should not detect unnecessary error handler when base can fail" in {
      val (_, wf)       = TestUtils.error
      val wfWithHandler = wf.handleErrorWith(TestUtils.errorHandler)

      val issues = Linter.lint(wfWithHandler)
      assert(issues.isEmpty)
    }
  }

}
