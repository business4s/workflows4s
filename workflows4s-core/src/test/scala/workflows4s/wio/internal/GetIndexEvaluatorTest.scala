package workflows4s.wio.internal

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import cats.Id
import workflows4s.testing.TestUtils
import workflows4s.wio.{TestCtx2, TestState}
import workflows4s.wio.WIO
import scala.util.Random

/** 2 options:
  *   - Create instance, execute WIO and get progress, extract WIO from progress.
  *   - Construct WIO structure manually: need specific builders for testing situation only
  */
class GetIndexEvaluatorTest extends AnyFreeSpec with Matchers {
  import workflows4s.wio.TestCtx2.WIO as TWIO

  "GetIndexEvaluator" - {

    "for a leaf WIO node" - {
      "should return None if the node is not executed" in {
        val (_, wio) = TestUtils.pure
        GetIndexEvaluator.findMaxIndex[Id, TestCtx2.Ctx, TestState, Nothing, TestState](wio) shouldBe None
      }

      "should return the index if the node is Executed" in {
        val index = Random.nextInt()
        val state = TestState(Nil, Nil)
        val pure  = TWIO.pure(state).done
        val wio   = WIO.Executed(pure, Right(state), state, index)
        GetIndexEvaluator.findMaxIndex[Id, TestCtx2.Ctx, TestState, Nothing, TestState](wio) shouldBe Some(index)
      }
    }

    "for a complex WIO node" - {
      "AndThen partially executed" in {
        val index = Random.nextInt()
        val state = TestState(Nil, Nil)
        val pure  = TWIO.pure(state).done

        val step1 = WIO.Executed(pure, Right(state), state, index)
        val step2 = TestUtils.pure._2

        val wio = step1 >>> step2

        GetIndexEvaluator.findMaxIndex[Id, TestCtx2.Ctx, TestState, Nothing, TestState](wio) shouldBe Some(index)
      }
    }
  }

}
