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
        (duration, timer.toInterruption.andThen(_ >>> step1), step1Id)
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
        instance.getExpectedSignals() shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.isLeft)
      }

      "handle signal - base" in {
        val wf            = handleSignalB.interruptWith(interruptWithSignalA)
        val (_, instance) = TestUtils.createInstance2(wf)

        instance.getExpectedSignals() should contain theSameElementsAs List(signalA, signalB)
        val signalResult = instance.deliverSignal(signalB, 42).value
        assert(signalResult === 42)
        assert(instance.queryState() === TestState(executed = List(signalBStepId)))

        // after base is processed, interruption is no longer possible
        instance.getExpectedSignals() shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.isLeft)
      }

      "handle signal - interruption" in {
        val wf            = handleSignalB.interruptWith(interruptWithSignalA)
        val (_, instance) = TestUtils.createInstance2(wf)

        instance.getExpectedSignals() should contain theSameElementsAs List(signalA, signalB)
        val signalResult = instance.deliverSignal(signalA, 42).value
        assert(signalResult === 42)

        // after processing, state is coming from interruption flow
        assert(instance.queryState() === TestState(executed = List(signalAStepId)))

        // Redelivery should return the original response (state unchanged)
        instance.getExpectedSignals() shouldBe empty
        val interruptionSignalResult = instance.deliverSignal(signalA, 43)
        assert(interruptionSignalResult.value === 42)
        assert(instance.queryState() === TestState(executed = List(signalAStepId)))
      }

      "getExpectedSignals with includeRedeliverable" in {
        val (signalC, signalCStepId, handleSignalC) = TestUtils.signal
        // Create a simple workflow: signalC followed by signalB
        val wf                                      = handleSignalC >>> handleSignalB
        val (_, instance)                           = TestUtils.createInstance2(wf)

        // Initially only the first signal (signalC) is pending - second hasn't been reached
        instance.getExpectedSignals() should contain theSameElementsAs List(signalC)
        // includeRedeliverable only includes executed signals, not future ones
        instance.getExpectedSignals(includeRedeliverable = true) should contain theSameElementsAs List(signalC)

        // Deliver the first signal
        val signalResult = instance.deliverSignal(signalC, 42).value
        assert(signalResult === 42)
        assert(instance.queryState().executed === List(signalCStepId))

        // After processing the first signal, only the second signal is pending
        instance.getExpectedSignals() should contain theSameElementsAs List(signalB)
        // With includeRedeliverable = true, both signals are returned (signalC is redeliverable, signalB is pending)
        instance.getExpectedSignals(includeRedeliverable = true) should contain theSameElementsAs List(signalC, signalB)
      }

      // TODO we could add more tests
      //  - proceed is evaluated on interruption flow after it was entered
      //  - base proceeed is not available while interruption flow was entered but not completed
      //  - base proceeed is not available while interruption flow was completed

      "interruption handler receives updated state after base partial execution" in {
        val (interruptSignalDef, interruptHandler) = {
          import TestCtx2.*
          val signalDef = SignalDef[Int, Int](id = "state-capturing-interrupt")
          case class InterruptEvent(stateLength: Int) extends TestCtx2.Event
          val stepId  = StepId.random("interrupt")
          val handler = WIO
            .handleSignal(signalDef)
            .using[TestState]
            .purely((state, _) => InterruptEvent(state.executed.length))
            .handleEvent((st, _) => st.addExecuted(stepId))
            .produceResponse((state, _, _) => state.executed.length)
            .done
          (signalDef, handler)
        }
        val (signalC, _, handleSignalC)            = TestUtils.signal

        val wf            = (handleSignalB >>> handleSignalC).interruptWith(interruptHandler.toInterruption)
        val (_, instance) = TestUtils.createInstance2(wf)

        instance.deliverSignal(signalB, 1).value
        assert(instance.queryState().executed.length === 1)

        val capturedStateLength = instance.deliverSignal(interruptSignalDef, 99).value
        assert(capturedStateLength === 1, "Interruption handler should see state after base partial execution")
      }

      "interruption handler receives updated state when nested inside AndThen" in {
        // Structure: signalFirst >>> (signalBase1 >>> signalBase2).interruptWith(interrupt)
        // Interrupt should see state = [signalFirst, signalBase1], NOT just [signalFirst]
        import TestCtx2.*

        val interruptSignalDef = SignalDef[Int, List[StepId]](id = "state-list-interrupt")
        case class InterruptEvent(stateLength: Int) extends TestCtx2.Event
        val interruptStepId  = StepId.random("interrupt")
        val interruptHandler = WIO
          .handleSignal(interruptSignalDef)
          .using[TestState]
          .purely((state, _) => InterruptEvent(state.executed.length))
          .handleEvent((st, _) => st.addExecuted(interruptStepId))
          .produceResponse((state, _, _) => state.executed)
          .done

        val (signalFirst, signalFirstStepId, handleSignalFirst) = TestUtils.signal
        val (signalBase1, signalBase1StepId, handleSignalBase1) = TestUtils.signal
        val (_, _, handleSignalBase2)                           = TestUtils.signal

        val innerInterrupted = (handleSignalBase1 >>> handleSignalBase2).interruptWith(interruptHandler.toInterruption)
        val wf               = handleSignalFirst >>> innerInterrupted
        val (_, instance)    = TestUtils.createInstance2(wf)

        instance.deliverSignal(signalFirst, 1).value
        instance.deliverSignal(signalBase1, 2).value
        assert(instance.queryState().executed === List(signalFirstStepId, signalBase1StepId))

        val capturedExecutedList = instance.deliverSignal(interruptSignalDef, 99).value
        assert(
          capturedExecutedList === List(signalFirstStepId, signalBase1StepId),
          "Interruption handler should see state changes from base, not just from parent AndThen",
        )
      }
    }

  }
}
