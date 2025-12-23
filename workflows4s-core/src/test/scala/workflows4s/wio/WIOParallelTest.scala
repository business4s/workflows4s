package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.TestUtils

class WIOParallelTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  "WIO.parallel" - {

    "should execute parallel elements and combine their results" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val (parStepId, wf) = createParallel(step1, step2)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(TestState.empty))
      val resultState   = instance.queryState()

      assert(resultState.executed == List(step1Id, step2Id, parStepId))
    }

    "should handle parallel execution with one element failing" in {
      val (step1Id, step1) = TestUtils.pure
      val (err, step2)     = TestUtils.error
      val (_, parallel)    = createParallel(step1, step2)

      val errHandler = TestUtils.errorHandler
      val wf         = parallel.handleErrorWith(errHandler)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(TestState.empty))
      val resultState   = instance.queryState()

      assert(resultState.executed == List(step1Id))
      assert(resultState.errors == List(err))
    }

    "should wait for all signals" in {
      val (signalDef1, singlaStepId1, step1) = TestUtils.signal
      val (signalDef2, singlaStepId2, step2) = TestUtils.signal

      val (parStepId, wf) = createParallel(step1, step2)

      val (_, instance) = TestUtils.createInstance2(wf)
      assert(instance.queryState().executed == List())

      instance.getExpectedSignals should contain theSameElementsAs (List(signalDef1, signalDef2))
      val response1 = instance.deliverSignal(signalDef1, 1).value
      assert(response1 == 1)
      assert(instance.queryState().executed == List(singlaStepId1))
      assert(instance.deliverSignal(signalDef1, 2).isLeft)

      instance.getExpectedSignals should contain theSameElementsAs (List(signalDef2))
      val response2 = instance.deliverSignal(signalDef2, 2).value
      assert(response2 == 2)
      assert(instance.queryState().executed == List(singlaStepId1, singlaStepId2, parStepId))
      assert(instance.deliverSignal(signalDef1, 2).isLeft)
    }

    "should wait for all timers" in {
      val (duration1, step1) = TestUtils.timer(secs = 1)
      val (duration2, step2) = TestUtils.timer(secs = 2)
      val (pure1Id, pure1)   = TestUtils.pure
      val (pure2Id, pure2)   = TestUtils.pure

      val (parStepId, wf) = createParallel(step1 >>> pure1, step2 >>> pure2)

      val (clock, instance) = TestUtils.createInstance2(wf)
      assert(instance.queryState().executed == List())
      instance.wakeup()

      clock.advanceBy(duration1)
      instance.wakeup()
      assert(instance.queryState().executed == List(pure1Id))

      clock.advanceBy(duration2 - duration1)
      instance.wakeup()
      assert(instance.queryState().executed == List(pure1Id, pure2Id, parStepId))
    }
  }

  def createParallel[Err](
      step1: TestCtx2.WIO[TestState, Err, TestState],
      step2: TestCtx2.WIO[TestState, Err, TestState],
  ): (StepId, TestCtx2.WIO[Any, Err, TestState]) = {
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
