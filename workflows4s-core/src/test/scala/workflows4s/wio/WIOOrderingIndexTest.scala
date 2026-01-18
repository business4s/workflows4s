package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.model.WIOExecutionProgress
import org.scalatest.compatible.Assertion
import workflows4s.wio.model.WIOExecutionProgress.Sequence
import cats.implicits.toTraverseOps

/* Mermaid chart of PullRequestWorkflow example:
 * https://mermaid.live/edit#pako:eNqdkkFzmzAQhf-KZnsFDwIEWIdMZuzJrR2P21NLDwostjpCokIkaR3_98pgk7RxLznqzX5P-550gMrUCBwaZR6rvbCOfFmXWnsx4pzjE1aDw_r2QPq96JCTStpKYUCUuEfFSQmfnYdKOE4QvQr1TtRyaF9RjeCNCFE_oDIdkpVF4ZBs8eeA_YtbRMLwhoy-kxB_K-E8u9mSqITvr-47bzAz8SQkntkOmmxkh0pqJPQaFs9YMgnp-5Js8UHi45skyWyfTgLzW22sqbDvL0x8ba90BtkkZB78iHY3NhCespwN58FsEvJTV8r042Dyn8yL8Ob5kuHeKEdWpm2lI5-MI3dm0HUJz6NpPud4g8zF3gmp8F-CXSHmkn5g5f4iKiX6fo0NuWxKGqkU_xBhVGoIYGdlDdzZAQNo0bbidIRDqYl_CrfHFks4vUqNjRiU77_UR491Qn81pr2Q1gy7Pfh1VO9PQ1f7D7WWYmfFywjqGu3KV-CAs9EB-AGegCfZghbRsshzxpYsXRYB_AJOs0VCi4zmNEoZjdkyPQbwe7wzWuRRwmJWpDROMkpTdvwDedQljw
 */
class WIOOrderingIndexTest extends AnyFreeSpec with Matchers {

  "WIO.Executed index" - {
    "Single step" in {
      val (id, wio)     = TestUtils.pure
      val (_, instance) = TestUtils.createInstance2(wio)
      assert(instance.getProgress.result.get.index == 0)
    }

    "WIO.AndThen 2 steps" in {
      val (id1, step1) = TestUtils.pure
      val (id2, step2) = TestUtils.pure

      val (_, instance) = TestUtils.createInstance2(step1 >>> step2)
      instance.getProgress match {
        case WIOExecutionProgress.Sequence(steps) =>
          steps.size shouldBe 2
          steps.head.result.map(_.index) shouldBe Some(0)
          steps.last.result.map(_.index) shouldBe Some(1)
        case _                                    => fail("Progress was not a Sequence")
      }
    }

    "WIO.AndThen 3 steps" in {
      val (id1, step1) = TestUtils.pure
      val (id2, step2) = TestUtils.pure
      val (id3, step3) = TestUtils.pure

      val (_, instance) = TestUtils.createInstance2(step1 >>> step2 >>> step3)
      instance.getProgress match {
        case WIOExecutionProgress.Sequence(steps) =>
          steps.size shouldBe 3
          steps.head.result.map(_.index) shouldBe Some(0)
          steps(1).result.map(_.index) shouldBe Some(1)
          steps.last.result.map(_.index) shouldBe Some(2)
        case _                                    => fail("Progress was not a Sequence")
      }
    }

    "WIO.HandleError" in {
      val (id1, step1) = TestUtils.pure

      val (id2, step2) = TestUtils.error

      val (id3, step3) = TestUtils.pure

      val step4 = TestUtils.errorHandler >>> step3

      val compositeWIO  = (step1 >>> step2).handleErrorWith(step4)
      val (_, instance) = TestUtils.createInstance2(compositeWIO)
      instance.getProgress match {
        case WIOExecutionProgress.HandleError(base, handler, _, step3Result) =>
          step3Result.map(_.index) shouldBe Some(3)
          base match {
            case WIOExecutionProgress.Sequence(steps) =>
              steps.forall(_.isExecuted) shouldBe true
              steps(0).result.map(_.index) shouldBe Some(0)
              steps(1).result.map(_.index) shouldBe Some(1)

            case _ => fail("Expected WIOExecutionProgress.Sequence")

          }

        case other => fail(s"Expected WIOExecutionProgress.HandleError")
      }
    }

    "WIO.FlatMap" in {
      val (_, wioA)     = TestUtils.pure
      val (_, wioB)     = TestUtils.pure
      val flatMappedWio = wioA.flatMap(_ => wioB)

      val (_, wf)  = TestUtils.createInstance2(flatMappedWio)
      val progress = wf.getProgress

      progress match {
        case flatMappedWioProgress @ WIOExecutionProgress.Sequence(steps) =>
          steps.head.result.map(_.index) shouldBe Some(0)
          steps.last.result.map(_.index) shouldBe Some(1)
          flatMappedWioProgress.result.map(_.index) shouldBe Some(1)
        case other                                                        =>
          fail(s"Expected WIOExecutionProgress.Sequence")
      }
    }
  }

  "WIO.Loop" in {
    val (_, loopBody) = TestUtils.pure

    val (_, restartStep) = TestUtils.pure

    val stopCondition: TestState => Boolean = state => {
      state.executed.size == 5
    }

    val repeatedWio = TestCtx2.WIO
      .repeat(loopBody)
      .until(stopCondition)
      .onRestart(restartStep)
      .done

    val (_, instance) = TestUtils.createInstance2(repeatedWio)
    instance.getProgress match {
      case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
        history.size shouldBe 5

        history.flatMap(_.result.map(_.index)).toList shouldBe List(0, 1, 2, 3, 4) // indices are in sync with history indices

      case other => fail(s"Expected WIOExecutionProgress.Loop")
    }
  }

  "WIO.Loop loopBody with internal Sequence, restart: 1 step" in {
    val loopBody = TestUtils.pure._2 >>> TestUtils.pure._2

    val (_, restartStep) = TestUtils.pure

    val stopCondition: TestState => Boolean = state => {
      state.executed.size > 2
    }

    val repeatedWio = TestCtx2.WIO
      .repeat(loopBody)
      .until(stopCondition)
      .onRestart(restartStep)
      .done

    val (_, instance) = TestUtils.createInstance2(repeatedWio)
    instance.getProgress match {
      case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
        history.size shouldBe 3

        history.flatMap(_.result.map(_.index)).toList shouldBe List(1, 2, 4) // Seq(0,1), Restart(2), Seq(3, 4)

      case _ => fail(s"Expected WIOExecutionProgress.Loop")
    }
  }

  "WIO.Parallel" in {
    import TestCtx2.*

    val (_, initialStep) = TestUtils.pure

    val (signal1, id1, step1) = TestUtils.signal
    val (signal2, id2, step2) = TestUtils.signal

    val wio = WIO.parallel
      .taking[TestState]
      .withInterimState[TestState](identity)
      .withElement(step1, _ ++ _)
      .withElement(step2, _ ++ _)
      .producingOutputWith((a, b) => (a ++ b).addExecuted(StepId.random))

    val (_, instance) = TestUtils.createInstance2(initialStep >>> wio)

    def assertIndex(id: StepId, expectedIndex: Int): Assertion = {
      instance.getProgress match {
        case WIOExecutionProgress.Sequence(steps) =>
          steps(1) match {
            case WIOExecutionProgress.Parallel(elements, result) =>
              val targetResult = elements.map(_.result).find(_.exists(_.value.exists(_.executed.contains(id)))).get.get
              val maxIndex     = elements.flatMap(_.result.map(_.index)).max
              assert(targetResult.index == expectedIndex)
              assert(result.forall(_.index == maxIndex)) // if Parralel is complete, its index = max index of elements

            case _ =>
              fail()
          }

        case _ =>
          fail()
      }
    }

    instance.deliverSignal(signal2, 2)
    assertIndex(id2, 1)

    instance.deliverSignal(signal1, 1)
    assertIndex(id1, 2)
  }

  "WIO.Parallel with error" in {
    import TestCtx2.*

    val (_, initialStep) = TestUtils.pure

    val (_, id1, step1) = TestUtils.signal // the signal won't be delivered to keep step1 pending
    val (id2, step2)    = TestUtils.pure   // expectedIndex : 1
    val (id3, step3)    = TestUtils.error  // expectedIdnex: 2

    val parallel = WIO.parallel
      .taking[TestState]
      .withInterimState[TestState](identity)
      .withElement(step1, _ ++ _)
      .withElement(step2, _ ++ _)
      .withElement(step3, _ ++ _)
      .producingOutputWith((a, b, c) => (a ++ b ++ c).addExecuted(StepId.random))

    val wio = (initialStep >>> parallel).handleErrorWith(
      TestUtils.errorHandler,
    )

    val (_, instance) = TestUtils.createInstance2(wio)

    instance.getProgress match {
      case WIOExecutionProgress.HandleError(base, _, _, result) =>
        base match {
          case WIOExecutionProgress.Sequence(steps) =>
            steps(1) match {
              case WIOExecutionProgress.Parallel(elements, parallelResult) =>
                assert(parallelResult.exists(_.index == 2))
              case _                                                       =>
                fail("expected WIOExecutionProgress.Parallel")
            }
          case _                                    =>
            fail("expected WIOExecutionProgress.Sequence")
        }

      case other @ _ =>
        fail("expected WIOExecutionProgress.HandleError")
    }
  }

  "WIO.Parallel pure -> signal" in {
    import TestCtx2.*

    val (signal1, _, stepSignal1) = TestUtils.signal
    val (signal2, _, stepSignal2) = TestUtils.signal

    val (_, pure1) = TestUtils.pure // executed index: 0
    val (_, pure2) = TestUtils.pure // executed index: 1

    val step1 = pure1 >>> stepSignal1
    val step2 = pure2 >>> stepSignal2

    val parallel = WIO.parallel
      .taking[TestState]
      .withInterimState[TestState](identity)
      .withElement(step1, _ ++ _)
      .withElement(step2, _ ++ _)
      .producingOutputWith((a, b) => (a ++ b).addExecuted(StepId.random))

    val (_, instance) = TestUtils.createInstance2(parallel)

    instance.deliverSignal(signal1, 1) // executed index: 2
    instance.deliverSignal(signal2, 2) // executed index: 3

    instance.getProgress match {
      case WIOExecutionProgress.Parallel(elements, parallelResult) =>
        parallelResult.map(_.index) shouldBe Some(3)
        val sequenceELements = elements.collect { case sq: WIOExecutionProgress.Sequence[?] => sq }
        sequenceELements.flatMap(_.steps.map(_.result.map(_.index))).sequence `shouldBe` Some(List(0, 2, 1, 3))
      case _                                                       =>
        fail("expected WIOExecutionProgress.Parallel")
    }
  }

  "WIO.Fork" in {
    val initStep = TestCtx2.WIO.pure
      .makeFrom[Int]
      .value(_ => TestState.empty)
      .done

    val (_, pureStep) = TestUtils.pure
    val oddPath       = initStep >>> pureStep
    val evenPath      = initStep >>> pureStep >>> pureStep // selected path

    val fork = TestCtx2.WIO
      .fork[Int]
      .matchCondition(_ % 2 != 0, "is input a odd number?")(
        oddPath,
        evenPath,
      )

    val (_, instance) = TestUtils.createInstance2(fork.provideInput(42))
    instance.getProgress match {
      case WIOExecutionProgress.Fork(branches, meta, selected) =>
        selected.map(branches) match {
          case Some(WIOExecutionProgress.Sequence(steps)) =>
            assert(steps.size == 3)
          case _                                          =>
            fail("expected WIOExecutionProgress.Sequence")
        }
      case _                                                   =>
        fail("expected WIOExecutionProgress.Fork")
    }
  }

  "WIO.Checkpoint" in {
    case object NoopEvent extends TestCtx2.Event

    val (_, step1) = TestUtils.pure
    val (_, step2) = TestUtils.pure
    val (_, step3) = TestUtils.pure

    val wio = (step1 >>> step2 >>> step3).checkpointed(
      (_, _) => NoopEvent,
      (_, _) => TestState.empty,
    )

    val (_, instance) = TestUtils.createInstance2(wio)
    instance.wakeup() // The checkpointing action itself is effectful and needs a wakeup

    instance.getProgress match {
      case WIOExecutionProgress.Checkpoint(base, result) =>
        base match {
          case WIOExecutionProgress.Sequence(steps) =>
            steps.size shouldBe 3
            steps.map(_.result.map(_.index).get) shouldBe List(0, 1, 2)
          case other                                => fail(s"Expected WIOExecutionProgress.Sequence inside Checkpoint")
        }
      case other                                         => fail(s"Expected WIOExecutionProgress.Checkpoint")
    }
  }

  "WIO.HandleInterruption" in {
    // 1. Setup a base workflow that waits for a signal
    val (_, _, handleSignalA) = TestUtils.signal

    // 2. Setup an interruption that fires after a timer and executes a simple step
    val (timerDuration, timer)                 = TestUtils.timer()
    val (interruptionStepId, interruptionStep) = TestUtils.pure
    val timerInterruption                      = timer.toInterruption.andThen[Nothing, TestState](_ >>> interruptionStep)

    // 3. Create the interruptible workflow
    val wio               = handleSignalA.interruptWith(timerInterruption)
    val (clock, instance) = TestUtils.createInstance2(wio)

    // 4. Trigger the interruption by advancing the clock
    instance.wakeup() // Starts the timer
    clock.advanceBy(timerDuration)
    instance.wakeup() // Processes the timer firing, which runs the interruption step

    // 5. Verify the execution progress and indices
    instance.getProgress match {
      case WIOExecutionProgress.Interruptible(base, trigger, handlerOpt, result) =>
        trigger.result.map(_.index) shouldBe Some(0)

        val handler = handlerOpt.getOrElse(fail("Handler was not defined"))
        handler.result.map(_.index) shouldBe Some(1)

        result.map(_.index) shouldBe Some(1)

        // The base logic should not have been executed
        base.isExecuted shouldBe false

      case other => fail(s"Expected WIOExecutionProgress.Interruptible")
    }

    instance.queryState().executed shouldBe List(interruptionStepId)
  }

  "WIO.RunIO (index is implemented in EventEvaluator)" in {
    val (pureStepId, pureStep)   = TestUtils.pure
    val (runIoStepId, runIoStep) = TestUtils.runIO

    val wio           = pureStep >>> runIoStep
    val (_, instance) = TestUtils.createInstance2(wio)

    instance.wakeup()

    instance.getProgress match {
      case WIOExecutionProgress.Sequence(steps) =>
        steps.size shouldBe 2
        steps(0).result.map(_.index) shouldBe Some(0)
        steps(1).result.map(_.index) shouldBe Some(1)
      case other                                => fail(s"Expected WIOExecutionProgress.Sequence, got $other")
    }

    instance.queryState().executed shouldBe List(pureStepId, runIoStepId)
  }

}
