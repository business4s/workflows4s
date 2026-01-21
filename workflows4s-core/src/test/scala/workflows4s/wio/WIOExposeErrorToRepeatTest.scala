package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIOExposeErrorToRepeatTest extends AnyFreeSpec with Matchers {

  import TestCtx2.*

  "WIO.exposeErrorToRepeat" - {

    "should expose error as state in repeat loop and allow retry on error" in {
      val (err, errorStep) = TestUtils.error
      val (step1Id, step1) = TestUtils.pure

      val failingWorkflow: WIO[TestState, String, TestState] =
        step1 >>> errorStep

      val exposedError: WIO[TestState, Nothing, TestState] =
        failingWorkflow.exposeErrorToRepeat { (error, state) =>
          state.addError(s"Caught error: $error")
        }

      val (_, wf)     = TestUtils.createInstance2(exposedError)
      val resultState = wf.queryState()

      assert(resultState.errors.exists(_.contains("Caught error")))
    }

    "should expose error while allowing successful case to pass through" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val workflow: WIO[TestState, Nothing, TestState] =
        (step1 >>> step2).exposeErrorToRepeat { (error, state) =>
          state.addError(s"Unexpected error: $error")
        }

      val (_, wf)     = TestUtils.createInstance2(workflow)
      val resultState = wf.queryState()

      assert(resultState.executed == List(step1Id, step2Id))
      assert(resultState.errors.isEmpty)
    }

    "should integrate with repeat loops for retry logic" in {
      val (err, errorStep) = TestUtils.error
      val (step1Id, step1) = TestUtils.pure

      val alwaysFails: WIO[TestState, String, TestState] =
        step1 >>> errorStep

      val exposedError: WIO[TestState, Nothing, TestState] =
        alwaysFails.exposeErrorToRepeat { (error, state) =>
          state.addError(s"Attempt failed: $error")
        }

      val loopWio: WIO[TestState, Nothing, TestState] =
        WIO
          .build[Ctx]
          .repeat(exposedError)
          .until { state =>
            state.errors.length > 2
          }
          .onRestartContinue
          .done

      val initialState = TestState.empty
      val (_, wf)      = TestUtils.createInstance2(loopWio.provideInput(initialState))
      val resultState  = wf.queryState()

      assert(resultState.errors.length >= 3)
    }

    "should preserve error information for custom handling" in {
      val (_, errorStep) = TestUtils.error

      val workflow: WIO[TestState, Nothing, TestState] =
        errorStep.exposeErrorToRepeat { (error, state) =>
          state.addError(s"Error handled: $error")
        }

      val (_, wf) = TestUtils.createInstance2(workflow)
      val result  = wf.queryState()

      assert(result.errors.nonEmpty)
    }

    "should work with effectful operations" in {
      val (duration, timerStep) = TestUtils.timer(secs = 1)
      val (step1Id, step1)      = TestUtils.pure

      val effectfulWorkflow: WIO[TestState, Nothing, TestState] =
        (step1 >>> timerStep).exposeErrorToRepeat { (error, state) =>
          state.addError(s"Error: $error")
        }

      val (clock, wf) = TestUtils.createInstance2(effectfulWorkflow)
      wf.wakeup()

      val resultState = wf.queryState()
      assert(resultState.executed.contains(step1Id))
      clock.advanceBy(duration)
      assert(resultState.executed.length >= 1)
    }

    "should handle multiple error exposures in a chain" in {
      val (err1, errorStep1) = TestUtils.error
      val (err2, errorStep2) = TestUtils.error
      val (step1Id, step1)   = TestUtils.pure

      val workflow: WIO[TestState, Nothing, TestState] =
        (step1 >>> errorStep1)
          .exposeErrorToRepeat { (error, state) =>
            state.addError(s"First error: $error")
          }
          >>> errorStep2
          .exposeErrorToRepeat { (error, state) =>
            state.addError(s"Second error: $error")
          }

      val (_, wf)     = TestUtils.createInstance2(workflow)
      val resultState = wf.queryState()

      assert(resultState.errors.size >= 2)
    }

  }

}
