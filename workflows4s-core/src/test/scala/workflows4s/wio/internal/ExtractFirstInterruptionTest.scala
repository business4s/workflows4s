package workflows4s.wio.internal

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}

class ExtractFirstInterruptionTest extends AnyFreeSpec with Matchers {

  private def signal(name: String): WIOExecutionProgress.HandleSignal[Int] =
    WIOExecutionProgress.HandleSignal(WIOMeta.HandleSignal(name, None, None), None)

  private def pure(name: String): WIOExecutionProgress.Pure[Int] =
    WIOExecutionProgress.Pure(WIOMeta.Pure(Some(name), None), None)

  "extractFirstInterruption" - {
    "should keep every step after the interruption in a 3-element sequence" in {
      val flow = WIOExecutionProgress.Sequence(Seq(signal("cancel"), pure("cleanup"), pure("notify")))

      val Some((_, Some(rest))) = ExecutionProgressEvaluator.extractFirstInterruption(flow): @unchecked

      rest shouldBe WIOExecutionProgress.Sequence(Seq(pure("cleanup"), pure("notify")))
    }
  }
}
