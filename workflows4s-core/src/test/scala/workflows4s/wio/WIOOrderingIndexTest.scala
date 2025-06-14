package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.model.WIOExecutionProgress

//TODO: WIO.Parallel, Loop with internal WIO.Sequence

class WIOOrderingIndexTest extends AnyFreeSpec with Matchers {

  "WIO.Index" - {
    "single step" in {
      val (id, wio) = TestUtils.pure
      val (_, wf)    = TestUtils.createInstance2(wio)
      val progress   = wf.getProgress
      assert(progress.result.get.index == 0)
    }

    "2 steps" in {
      val (id1, step1) = TestUtils.pure
      val (id2, step2) = TestUtils.pure

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


    "3 steps" in {
      val (id1, step1) = TestUtils.pure
      val (id2, step2) = TestUtils.pure
      val (id3, step3) = TestUtils.pure

      val (_, wf)  = TestUtils.createInstance2(step1 >>> step2 >>> step3)
      val progress = wf.getProgress
      progress match {
        case WIOExecutionProgress.Sequence(steps) =>
          steps.size shouldBe 3
          steps.head.result.map(_.index) shouldBe Some(0)
          steps(1).result.map(_.index) shouldBe Some(1)
          steps.last.result.map(_.index) shouldBe Some(2)
        case _                                    => fail("Progress was not a Sequence")
      }
    }

    "2 steps with error handling" in {
      val (id1, step1)= TestUtils.pure

      val (id2, step2) = TestUtils.error

      val (id3, step3) = TestUtils.pure

      val step4 = TestUtils.errorHandler >>> step3

      val compositeWIO = (step1 >>> step2).handleErrorWith(step4)
      val (_, wfInstance)                         = TestUtils.createInstance2(compositeWIO)

      val progress = wfInstance.getProgress

      progress match {
        case WIOExecutionProgress.HandleError(base, handler, _, step3Result) =>
          step3Result.map(_.index) shouldBe Some(3)
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

    "WIO.FlatMap" in {
      val (_, wioA) = TestUtils.pure
      val (_, wioB) = TestUtils.pure
      val flatMappedWio = wioA.flatMap(_ => wioB)

      val (_, wf)  = TestUtils.createInstance2(flatMappedWio)
      val progress = wf.getProgress

      progress match {
        case flatMappedWioProgress @ WIOExecutionProgress.Sequence(steps) =>
          steps.head.result.map(_.index) shouldBe Some(0)
          steps.last.result.map(_.index) shouldBe Some(1)
          flatMappedWioProgress.result.map(_.index) shouldBe Some(1)
        case other                                                        =>
          fail(s"Expected WIOExecutionProgress.Sequence, got ${other.getClass.getSimpleName} with value $other")
      }
    }
  }

  // "WIO.Loop " in {
  //
  //   val (_, loopBody) = TestUtils.pure
  //
  //   val restartStep = TestUtils.pure
  //
  //   val stopCondition: String => Boolean = bodyOutputState => {
  //     val parts = bodyOutputState.split('_')
  //     val count = parts.last.toInt
  //     count >= 2
  //   }
  //
  //   val repeatedWio: WIO[String, Nothing, String] = WIO
  //     .repeat(loopBody)
  //     .until(stopCondition)
  //     .onRestart(restartStep)
  //     .done
  //
  //   val (_, wf)      = TestUtils.createInstance2(repeatedWio.provideInput(initialStateForLoopWIO))
  //   val loopProgress = wf.getProgress
  //
  //   loopProgress match {
  //     case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
  //       history.size shouldBe 3 // 3: iter1, restart, iter2
  //
  //       //iter1
  //       history.head.result.flatMap(_.value.toOption) shouldBe Some(s"${baseText}_iter1_1")
  //       history.head.result.map(_.index) shouldBe Some(0)
  //
  //       //restart
  //       history(1).result.flatMap(_.value.toOption) shouldBe Some(s"step_iter1_1")
  //       history(1).result.map(_.index) shouldBe Some(1)
  //
  //       //iter2
  //       history.last.result.flatMap(_.value.toOption) shouldBe Some(s"${baseText}_iter1_iter2_2")
  //       history.last.result.map(_.index) shouldBe Some(2)
  //
  //     case other => fail(s"Expected WIOExecutionProgress.Loop, got ${other.getClass.getSimpleName} ($other)")
  //   }
  // }
  //
  // "WIO.Loop body 2 steps" in {
  //   val step1 = WIO.pure("step1").autoNamed
  //   val step2 = WIO.pure.makeFrom[String].value(s => s"$s >>> step2").autoNamed
  //
  //   val loopBody: WIO[String, Nothing, String] = step1 >>> step2
  //
  //   val restartStep = WIO.pure.makeFrom[String].value(identity).autoNamed
  //
  //   val stopCondition: String => Boolean = bodyOutputState => {
  //     bodyOutputState.size > 100
  //   }
  //
  //   val repeatedWio: WIO[String, Nothing, String] = WIO
  //     .repeat(loopBody)
  //     .until(stopCondition)
  //     .onRestart(restartStep)
  //     .done
  //
  //   val (_, wf)      = TestUtils.createInstance(repeatedWio.provideInput("init"))
  //   val loopProgress = wf.getProgress
  //
  //   loopProgress match {
  //     case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
  //       history.size shouldBe 3 // 3: iter1, restart, iter2
  //
  //     case other => fail(s"Expected WIOExecutionProgress.Loop, got ${other.getClass.getSimpleName} ($other)")
  //   }
  // }
}
