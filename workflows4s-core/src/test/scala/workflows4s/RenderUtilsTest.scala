package workflows4s

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.model.WIOExecutionProgress.ExecutedResult
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta, WIOModel}

class RenderUtilsTest extends AnyFreeSpec with Matchers {

  private val loopMeta = WIOMeta.Loop(None, None, None)

  private def loop(history: Seq[WIOExecutionProgress[String]]): WIOExecutionProgress.Loop[String] =
    WIOExecutionProgress.Loop(WIOModel.End, None, loopMeta, history)

  "RenderUtils.hasStarted" - {
    "reports a loop as not started when its history only holds the not-yet-executed body" in {
      RenderUtils.hasStarted(loop(Seq(WIOExecutionProgress.End(None)))) shouldBe false
    }

    "reports a loop as started once a history entry has executed" in {
      val executed = WIOExecutionProgress.End(Some(ExecutedResult(Right("done"), 0)))
      RenderUtils.hasStarted(loop(Seq(executed))) shouldBe true
    }
  }
}
