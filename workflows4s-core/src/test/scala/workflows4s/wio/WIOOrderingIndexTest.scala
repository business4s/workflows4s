package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.model.WIOExecutionProgress

class WIOOrderingIndexTest extends AnyFreeSpec with Matchers{

  import TestCtx.*

  "WIO.Index" - {

    "single step" in {
      val singleStep = WIO.pure("someState").done
      val (_, wf) = TestUtils.createInstance(singleStep)
      val progress = wf.getProgress
      assert(progress.result.get.index == 0)
    }

    "single signal step" in {
      val signalDef = SignalDef[Unit, Unit]("TestSignalUnit")
      val signalHandlingWio: WIO[State, Nothing, State] =
        WIO.handleSignal(signalDef)
          .using[State]
          .purely { (_, _) => SimpleEvent("SignalProcessed") }
          .handleEvent { (prevState, event) => s"${prevState}_${event.value}" }
          .voidResponse
          .done

      val initialSignalWio: WIO.Initial = signalHandlingWio.provideInput("WrapperInputForSignal")
      val (_, wfInstance) = TestUtils.createInstance(initialSignalWio)

      wfInstance.deliverSignal(signalDef, ()) shouldBe Right(())

      val progress = wfInstance.getProgress
      progress match {
        case WIOExecutionProgress.HandleSignal(_, resultOpt) =>
          resultOpt should (be (defined) and not be empty)
          resultOpt.get.index shouldBe 0
        case other =>
          fail(s"Expected WIOExecutionProgress.HandleSignal, got ${other.getClass.getSimpleName}")
      }
    }

    "single signal 2 steps" in {
      val signalDef = SignalDef[Unit, Unit]("TestSignalUnit")
      val signalHandlingWio: WIO[State, Nothing, State] =
        WIO.handleSignal(signalDef)
          .using[State]
          .purely { (_, _) => SimpleEvent("SignalProcessed") }
          .handleEvent { (prevState, event) => s"${prevState}_${event.value}" }
          .voidResponse
          .done

      val initialSignalWio: WIO.Initial = signalHandlingWio.provideInput("WrapperInputForSignal")
      val (_, wfInstance) = TestUtils.createInstance(initialSignalWio)

      wfInstance.deliverSignal(signalDef, ()) shouldBe Right(())

      val progress = wfInstance.getProgress
      progress match {
        case WIOExecutionProgress.HandleSignal(_, resultOpt) =>
          resultOpt should (be (defined) and not be empty)
          resultOpt.get.index shouldBe 0
        case other =>
          fail(s"Expected WIOExecutionProgress.HandleSignal, got ${other.getClass.getSimpleName}")
      }
    }


    "2 steps" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val (_, wf) = TestUtils.createInstance2(step1 >>> step2)
      val progress = wf.getProgress
      progress match {
        case WIOExecutionProgress.Sequence(steps) => 
            steps.size shouldBe 2
            steps.head.result.map(_.index) shouldBe Some(0)
            steps.last.result.map(_.index) shouldBe Some(1)
        case _                                    => fail("Progress was not a Sequence")
      }
    }
  }
}
