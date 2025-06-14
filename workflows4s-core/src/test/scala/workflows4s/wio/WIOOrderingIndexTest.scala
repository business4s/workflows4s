package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.model.WIOExecutionProgress

//TODO: WIO.Parallel
/* Mermaid chart of PullRequestWorkflow example: 
 * https://mermaid.live/edit#pako:eNqdkkFzmzAQhf-KZnsFDwIEWIdMZuzJrR2P21NLDwostjpCokIkaR3_98pgk7RxLznqzX5P-550gMrUCBwaZR6rvbCOfFmXWnsx4pzjE1aDw_r2QPq96JCTStpKYUCUuEfFSQmfnYdKOE4QvQr1TtRyaF9RjeCNCFE_oDIdkpVF4ZBs8eeA_YtbRMLwhoy-kxB_K-E8u9mSqITvr-47bzAz8SQkntkOmmxkh0pqJPQaFs9YMgnp-5Js8UHi45skyWyfTgLzW22sqbDvL0x8ba90BtkkZB78iHY3NhCespwN58FsEvJTV8r042Dyn8yL8Ob5kuHeKEdWpm2lI5-MI3dm0HUJz6NpPud4g8zF3gmp8F-CXSHmkn5g5f4iKiX6fo0NuWxKGqkU_xBhVGoIYGdlDdzZAQNo0bbidIRDqYl_CrfHFks4vUqNjRiU77_UR491Qn81pr2Q1gy7Pfh1VO9PQ1f7D7WWYmfFywjqGu3KV-CAs9EB-AGegCfZghbRsshzxpYsXRYB_AJOs0VCi4zmNEoZjdkyPQbwe7wzWuRRwmJWpDROMkpTdvwDedQljw
 */
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

    val (_, wf)      = TestUtils.createInstance2(repeatedWio)
    val loopProgress = wf.getProgress

    loopProgress match {
      case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
        history.size shouldBe 5

        history.flatMap(_.result.map(_.index)).toList shouldBe List(0, 1, 2, 3, 4) // indices are in sync with history indices

      case other => fail(s"Expected WIOExecutionProgress.Loop, got ${other.getClass.getSimpleName} ($other)")
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

    val (_, wf)      = TestUtils.createInstance2(repeatedWio)
    val loopProgress = wf.getProgress

    loopProgress match {
      case lp @ WIOExecutionProgress.Loop(_, _, meta, history) =>
        history.size shouldBe 3

        history.flatMap(_.result.map(_.index)).toList shouldBe List(1, 2, 4) // Seq(0,1), Restart(2), Seq(3, 4)

      case other => fail(s"Expected WIOExecutionProgress.Loop, got ${other.getClass.getSimpleName} ($other)")
    }
  }
}
