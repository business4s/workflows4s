package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.TestUtils

class WIOForkTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  "WIO.fork" - {

    "should execute parallel elements and combine their results" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val (parStepId, wf) = createParallel(step1, step2)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(TestState.empty))
      val resultState   = instance.queryState()

      assert(resultState.executed == List(step1Id, step2Id, parStepId))
    }
  }

  def createParallel[Err](
      step1: TestCtx2.WIO[TestState, Err, TestState],
      step2: TestCtx2.WIO[TestState, Err, TestState],
  ): (StepId, WIO[Any, Err, TestState, TestCtx2.Ctx]) = {
    val parStepId = StepId.random
    val wf        = TestCtx2.WIO.parallel
      .taking[TestState]
      .withInterimState[TestState](in => in)
      .withElement(step1, _ ++ _)
      .withElement(step2, _ ++ _)
      .producingOutputWith((a, b) => (a ++ b).addExecuted(parStepId))
    (parStepId, wf.provideInput(TestState.empty))
  }

}
