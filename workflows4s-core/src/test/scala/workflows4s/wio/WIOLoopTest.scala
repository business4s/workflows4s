package workflows4s.wio

import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.TestUtils

import scala.util.chaining.scalaUtilChainingOps

class WIOLoopTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  "WIO.Loop" - {

    "should execute pure loop until condition is met" - {
      "going forward" in {
        val (step1Id, step1) = TestUtils.pure
        val iterations       = 3

        val (loopFinishedId, wf) = createLoop(step1, step1Id, None, iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        val resultState = instance.queryState()
        assert(resultState.executed == List(step1Id, step1Id, step1Id, loopFinishedId))
      }
      "going backward" in {
        val (step1Id, step1) = TestUtils.pure
        val (step2Id, step2) = TestUtils.pure
        val iterations       = 2

        val (loopFinishedId, wf) = createLoop(step1, step1Id, Some(step2), iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        val resultState = instance.queryState()
        assert(resultState.executed == List(step1Id, step2Id, step1Id, loopFinishedId))
      }
    }

    "should execute effectful loop until condition is met" - {
      "going forward" in {
        val (step1Id, step1) = TestUtils.runIO
        val iterations       = 3

        val (loopFinishedId, wf) = createLoop(step1, step1Id, None, iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        instance.wakeup()
        val resultState = instance.queryState()
        assert(resultState.executed == List(step1Id, step1Id, step1Id, loopFinishedId))
      }
      "going backward" in {
        val (step1Id, step1) = TestUtils.pure
        val (step2Id, step2) = TestUtils.runIO
        val iterations       = 2

        val (loopFinishedId, wf) = createLoop(step1, step1Id, Some(step2), iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        instance.wakeup()
        val resultState = instance.queryState()
        assert(resultState.executed == List(step1Id, step2Id, step1Id, loopFinishedId))
      }
    }

    "should handle pure errors in loop" - {
      "going forward" in {
        val (err, errorStep) = TestUtils.error
        val (step1Id, step1) = TestUtils.pure

        val (_, loopWf) = createLoop(step1 >>> errorStep, step1Id, None, 1)
        val wf          = loopWf.handleErrorWith(TestUtils.errorHandler)

        val (_, instance) = TestUtils.createInstance2(wf)
        val resultState   = instance.queryState()

        assert(resultState.errors.contains(err))
        assert(resultState.executed == List(step1Id))
      }
      "going backward" in {
        val (err, errorStep) = TestUtils.error
        val (step1Id, step1) = TestUtils.pure
        val (step2Id, step2) = TestUtils.pure

        val (_, loopWf) = createLoop(step1, step1Id, (step2 >>> errorStep).some, 2)
        val wf          = loopWf.handleErrorWith(TestUtils.errorHandler)

        val (_, instance) = TestUtils.createInstance2(wf)
        val resultState   = instance.queryState()

        assert(resultState.errors.contains(err))
        assert(resultState.executed == List(step1Id, step2Id))
      }
    }

    "should handle effectful errors in loop" - {
      "going forward" in {
        val (err, errorStep) = TestUtils.errorIO
        val (step1Id, step1) = TestUtils.pure

        val (_, loopWf) = createLoop(step1 >>> errorStep, step1Id, None, 1)
        val wf          = loopWf.handleErrorWith(TestUtils.errorHandler)

        val (_, instance) = TestUtils.createInstance2(wf)
        instance.wakeup()
        val resultState   = instance.queryState()

        assert(resultState.errors.contains(err))
        assert(resultState.executed == List(step1Id))
      }
      "going backward" in {
        val (err, errorStep) = TestUtils.errorIO
        val (step1Id, step1) = TestUtils.pure
        val (step2Id, step2) = TestUtils.pure

        val (_, loopWf) = createLoop(step1, step1Id, (step2 >>> errorStep).some, 2)
        val wf          = loopWf.handleErrorWith(TestUtils.errorHandler)

        val (_, instance) = TestUtils.createInstance2(wf)
        instance.wakeup()
        val resultState   = instance.queryState()

        assert(resultState.errors.contains(err))
        assert(resultState.executed == List(step1Id, step2Id))
      }
    }

    "should handle signals in loop" - {
      "going forward" in {
        val (signalDef, signalStepId, signalStep) = TestUtils.signal
        val iterations                            = 2

        val (loopFinishedId, wf) = createLoop(signalStep, signalStepId, None, iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        instance.getExpectedSignals should contain theSameElementsAs (List(signalDef))

        val response1 = instance.deliverSignal(signalDef, 1).value
        assert(response1 == 1)
        assert(instance.queryState().executed == List(signalStepId))

        instance.getExpectedSignals should contain theSameElementsAs (List(signalDef))

        // Deliver signal for second iteration
        val response2 = instance.deliverSignal(signalDef, 2).value
        assert(response2 == 2)
        assert(instance.queryState().executed == List(signalStepId, signalStepId, loopFinishedId))

        // Signal should no longer be accepted
        assert(instance.deliverSignal(signalDef, 3).isLeft)
      }

      "going backward" in {
        val (step1Id, step1)                      = TestUtils.pure
        val (signalDef, signalStepId, signalStep) = TestUtils.signal
        val iterations                            = 3

        val (loopFinishedId, wf) = createLoop(step1, step1Id, Some(signalStep), iterations)
        val (_, instance)        = TestUtils.createInstance2(wf)

        instance.getExpectedSignals should contain theSameElementsAs (List(signalDef))
        val response1 = instance.deliverSignal(signalDef, 1).value
        assert(response1 == 1)
        assert(instance.queryState().executed == List(step1Id, signalStepId, step1Id))

        instance.getExpectedSignals should contain theSameElementsAs (List(signalDef))
        // Deliver signal for second iteration
        val response2 = instance.deliverSignal(signalDef, 2).value
        assert(response2 == 2)
        assert(instance.queryState().executed == List(step1Id, signalStepId, step1Id, signalStepId, step1Id, loopFinishedId))

        // Signal should no longer be accepted
        instance.getExpectedSignals shouldBe empty
        assert(instance.deliverSignal(signalDef, 3).isLeft)
      }
    }

    "should handle timers in loop body" - {
      "going forward" in {
        val (duration1, timerStep) = TestUtils.timer(secs = 1)
        val (step1Id, step1)       = TestUtils.pure
        val iterations             = 2

        val (loopFinishedId, wf) = createLoop(step1 >>> timerStep, step1Id, None, iterations)
        val (clock, instance)    = TestUtils.createInstance2(wf)

        instance.wakeup()
        assert(instance.queryState().executed == List(step1Id))

        // First timer
        clock.advanceBy(duration1)
        instance.wakeup()
        assert(instance.queryState().executed == List(step1Id, step1Id))

        // Second timer
        clock.advanceBy(duration1)
        instance.wakeup()

        assert(instance.queryState().executed == List(step1Id, step1Id, loopFinishedId))
      }

      "going backward" in {
        val (duration1, timerStep) = TestUtils.timer(secs = 1)
        val (step1Id, step1)       = TestUtils.pure
        val (step2Id, step2)       = TestUtils.pure
        val iterations             = 3

        val (loopFinishedId, wf) = createLoop(step1, step1Id, Some(step2 >>> timerStep), iterations)
        val (clock, instance)    = TestUtils.createInstance2(wf)

        instance.wakeup()
        assert(instance.queryState().executed == List(step1Id, step2Id))

        // First timer
        clock.advanceBy(duration1)
        instance.wakeup()
        assert(instance.queryState().executed == List(step1Id, step2Id, step1Id, step2Id))

        // Second timer
        clock.advanceBy(duration1)
        instance.wakeup()

        assert(instance.queryState().executed == List(step1Id, step2Id, step1Id, step2Id, step1Id, loopFinishedId))
      }
    }
  }

  def createLoop[Err](
      body: TestCtx2.WIO[TestState, Err, TestState],
      bodyId: StepId,
      onReturn: Option[TestCtx2.WIO[TestState, Err, TestState]],
      maxIterations: Int,
  ): (StepId, TestCtx2.WIO[TestState, Err, TestState]) = {
    val (finishedStepId, finishedStep) = TestUtils.pure

    val loop = TestCtx2.WIO
      .repeat(body)
      .until(_.executed.groupBy(identity)(bodyId).size == maxIterations)
      .pipe(x =>
        onReturn match {
          case Some(value) => x.onRestart(value)
          case None        => x.onRestartContinue
        },
      )
      .done

    val wf = loop >>> finishedStep

    (finishedStepId, wf)
  }
}
