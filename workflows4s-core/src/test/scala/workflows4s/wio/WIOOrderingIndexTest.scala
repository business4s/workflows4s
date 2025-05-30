package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.model.WIOExecutionProgress

class WIOOrderingIndexTest extends AnyFreeSpec with Matchers {
  import TestCtx.*

  //TODO: test case for WIO.FlatMap, Loop

  "WIO.Index" - {

    "single step" in {
      val singleStep = WIO.pure("someState").done
      val (_, wf)    = TestUtils.createInstance(singleStep)
      val progress   = wf.getProgress
      assert(progress.result.get.index == 0)
    }

    "single signal step" in {
      val signalDef                                     = SignalDef[Unit, Unit]("TestSignalUnit")
      val signalHandlingWio: WIO[State, Nothing, State] =
        WIO
          .handleSignal(signalDef)
          .using[State]
          .purely { (_, _) => SimpleEvent("SignalProcessed") }
          .handleEvent { (prevState, event) => s"${prevState}_${event.value}" }
          .voidResponse
          .done

      val initialSignalWio: WIO.Initial = signalHandlingWio.provideInput("WrapperInputForSignal")
      val (_, wfInstance)               = TestUtils.createInstance(initialSignalWio)

      wfInstance.deliverSignal(signalDef, ()) shouldBe Right(())

      val progress = wfInstance.getProgress
      progress match {
        case WIOExecutionProgress.HandleSignal(_, resultOpt) =>
          resultOpt should (be(defined) and not be empty)
          resultOpt.get.index shouldBe 0
        case other                                           =>
          fail(s"Expected WIOExecutionProgress.HandleSignal, got ${other.getClass.getSimpleName}")
      }
    }

    "single signal 2 steps" in {
      val signalDef                                     = SignalDef[Unit, Unit]("TestSignalUnit")
      val signalHandlingWio: WIO[State, Nothing, State] =
        WIO
          .handleSignal(signalDef)
          .using[State]
          .purely { (_, _) => SimpleEvent("SignalProcessedP") }
          .handleEvent { (prevState, event) => s"${prevState}_${event.value}" }
          .voidResponse
          .done

      val initialSignalWio: WIO.Initial = signalHandlingWio.provideInput("WrapperInputForSignal")
      val (_, wfInstance)               = TestUtils.createInstance(initialSignalWio)

      wfInstance.deliverSignal(signalDef, ()) shouldBe Right(())

      val progress = wfInstance.getProgress
      progress match {
        case WIOExecutionProgress.HandleSignal(_, resultOpt) =>
          resultOpt should (be(defined) and not be empty)
          resultOpt.get.index shouldBe 0
        case other                                           =>
          fail(s"Expected WIOExecutionProgress.HandleSignal, got ${other.getClass.getSimpleName}")
      }
    }

    "2 steps" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val (_, wf)  = TestUtils.createInstance2(step1 >>> step2)
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

  "A WIO.HandleErrorWith" - {
    "2 steps including error handling" in {
      type Err = Int
      val FixedError                                             = 41
      val step1: WIO[Any, Err, String]                           = WIO.pure
        .makeFrom[Any]
        .error(_ => FixedError)
        .autoNamed
      val errorHandlingStep: WIO[(String, Err), Nothing, String] = WIO.pure
        .makeFrom[(String, Err)]
        .value((_, error) => s"Completed handling error $error")
        .autoNamed

      val wio: WIO[Any, Nothing, String] = step1.handleErrorWith(errorHandlingStep)

      val (_, wf)  = TestUtils.createInstance(wio)
      val progress = wf.getProgress
      progress match {
        case WIOExecutionProgress.HandleError(base, handler, _, finalResult) =>
          finalResult.map(_.index) shouldBe Some(1)
          base match {
            case WIOExecutionProgress.Pure(meta, result) =>
              result.map(_.value.isLeft) shouldBe Some(true)
              result.map(_.index) shouldBe Some(0)

            case _ => fail("Base of HandleError was not a Sequence")
          }

        case other => fail(s"Progress was not a HandleError")
      }
    }
    "should assign correct ordering indices when a step in the main flow errors and is handled" in {
      type Err = Int
      val FixedError = 41

      val step1: WIO[Any, Nothing, String] = WIO.pure("step1").autoNamed

      val step2: WIO[String, Err, Nothing] = WIO.pure
        .makeFrom[String]
        .error(_ => FixedError)
        .autoNamed // just produce an error

      val step3: WIO[(String, Err), Nothing, String] = WIO.pure
        .makeFrom[(String, Err)]
        .value { case (stateBeforeError, errorMsg) => "what?" }
        .autoNamed

      val compositeWIO: WIO[Any, Nothing, String] = (step1 >>> step2).handleErrorWith(step3)
      val (_, wfInstance)                         = TestUtils.createInstance(compositeWIO)

      val progress = wfInstance.getProgress

      progress match {
        case WIOExecutionProgress.HandleError(base, handler, _, step3Result) =>
          step3Result.map(_.index) shouldBe Some(2)
          base match {
            case WIOExecutionProgress.Sequence(steps) =>
              steps.forall(_.isExecuted) shouldBe true
              steps(0).result.map(_.index) shouldBe Some(0)
              steps(1).result.map(_.index) shouldBe Some(1)

            case _ => fail("Base of HandleError was not a Sequence")

          }

        case other => fail(s"Progress was not a HandleError")
      }
    }
  }

  "should assign correct ordering indices when rename me" in {
    type Err = Int
    val FixedError = 41

    val errorTriggerSignal: SignalDef[Unit, Unit] = SignalDef()

    val step1: WIO[Any, Nothing, String] = WIO.pure("step1").autoNamed

    val step2: WIO[String, Err, Nothing] = WIO
      .handleSignal(errorTriggerSignal)
      .using[String]
      .purely { (_, _) => SimpleEvent("SignalProcessed") }
      .handleEventWithError((in, event) => Left(FixedError), // just produce an error
      )
      .voidResponse
      .autoNamed

    val step3: WIO[(String, Err), Nothing, String] = WIO.pure
      .makeFrom[(String, Err)]
      .value { case (stateBeforeError, errorMsg) => "what?" }
      .autoNamed

    val compositeWIO: WIO[Any, Nothing, String] = (step1 >>> step2).handleErrorWith(step3)
    val (_, wfInstance)                         = TestUtils.createInstance(compositeWIO)
    wfInstance.deliverSignal(errorTriggerSignal, ())

    val progress = wfInstance.getProgress

    progress match {
      case WIOExecutionProgress.HandleError(base, handler, _, step3Result) =>
        step3Result.map(_.index) shouldBe Some(2)
        base match {
          case WIOExecutionProgress.Sequence(steps) =>
            steps.forall(_.isExecuted) shouldBe true
            steps(0).result.map(_.index) shouldBe Some(0)
            steps(1).result.map(_.index) shouldBe Some(1)

          case _ => fail("Base of HandleError was not a Sequence")

        }

      case other => fail(s"Progress was not a HandleError")
    }
  }
}
