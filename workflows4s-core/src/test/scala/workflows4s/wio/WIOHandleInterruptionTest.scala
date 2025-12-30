package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.TestUtils

import scala.concurrent.duration.DurationInt

class WIOHandleInterruptionTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  val (signalA, signalAStepId, handleSignalA) = TestUtils.signal
  val interruptWithSignalA                    = handleSignalA.toInterruption

  "WIO.HandleInterruption" - {

    "timer interruption" - {
      val (timerDuration, timerInterruption, interruptionStepId) = {
        val (duration, timer) = TestUtils.timer()
        val (step1Id, step1)  = TestUtils.pure
        (duration, timer.toInterruption.andThen[Nothing, TestState](_ >>> step1), step1Id)
      }

      "trigger interruption" in {
        val wf                = handleSignalA.interruptWith(timerInterruption)
        val (clock, instance) = TestUtils.createInstance2(wf)

        instance.wakeup()
        clock.advanceBy(timerDuration.plus(1.second))
        instance.wakeup()

        assert(instance.queryState() === TestState(executed = List(interruptionStepId)))
      }

      "proceed on base" in {
        val wf            = handleSignalA.interruptWith(timerInterruption)
        val (_, instance) = TestUtils.createInstance2(wf)

        val resp = instance.deliverSignal(signalA, 43).value
        assert(resp === 43)
        assert(instance.queryState() === TestState(executed = List(signalAStepId)))
      }
    }

    "signal interruption" - {
      val (signalB, signalBStepId, handleSignalB) = TestUtils.signal

      "initial state" in {
        val (step1Id, step1) = TestUtils.pure
        val wf               = step1.interruptWith(interruptWithSignalA)
        val (_, instance)    = TestUtils.createInstance2(wf)

        assert(instance.queryState() === TestState(executed = List(step1Id)))
      }

      "effectful proceed on base" in {
        val (step1Id, step1) = TestUtils.runIO
        val wf               = step1.interruptWith(interruptWithSignalA)
        val (_, instance)    = TestUtils.createInstance2(wf)

        instance.wakeup()
        assert(instance.queryState() === TestState(executed = List(step1Id)))

        // after base is processed, interruption is no longer possible
        instance.getExpectedSignals shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.isLeft)
      }

      "handle signal - base" in {
        val wf            = handleSignalB.interruptWith(interruptWithSignalA)
        val (_, instance) = TestUtils.createInstance2(wf)

        instance.getExpectedSignals should contain theSameElementsAs List(signalA, signalB)
        val signalResult = instance.deliverSignal(signalB, 42).value
        assert(signalResult === 42)
        assert(instance.queryState() === TestState(executed = List(signalBStepId)))

        // after base is processed, interruption is no longer possible
        instance.getExpectedSignals shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.isLeft)
      }

      "handle signal - interruption" in {
        val wf            = handleSignalB.interruptWith(interruptWithSignalA)
        val (_, instance) = TestUtils.createInstance2(wf)

        instance.getExpectedSignals should contain theSameElementsAs List(signalA, signalB)
        val signalResult = instance.deliverSignal(signalA, 42).value
        assert(signalResult === 42)

        // after processing, state is coming from interruption flow
        assert(instance.queryState() === TestState(executed = List(signalAStepId)))

        // after interruption is processed, interruption is no longer possible
        instance.getExpectedSignals shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.isLeft)
      }

      // TODO we could add more tests
      //  - proceed is evaluated on interruption flow after it was entered
      //  - base proceeed is not available while interruption flow was entered but not completed
      //  - base proceeed is not available while interruption flow was completed
    }

  }
}
