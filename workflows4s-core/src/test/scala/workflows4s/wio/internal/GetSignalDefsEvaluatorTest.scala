package workflows4s.wio.internal

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.{TestCtx2, TestState, WIO}
import cats.effect.IO
import java.time.Duration

class GetSignalDefsEvaluatorTest extends AnyFreeSpec with Matchers {

  "GetSignalDefsEvaluator" - {

    "for a simple HandleSignal" - {
      "should return the SignalDef" in {
        val (signalDef, _, wio) = TestUtils.signal
        GetSignalDefsEvaluator.run(wio).should(contain).theSameElementsAs(List(signalDef))
      }

      "should return an empty list if the signal is executed" in {
        val (_, _, wio) = TestUtils.signal
        val executedWio = WIO.Executed(wio, Right(TestState(Nil, Nil)), TestState(Nil, Nil), 1)
        GetSignalDefsEvaluator.run(executedWio).shouldBe(empty)
      }

      "should return an empty list if the signal is discarded" in {
        val (_, _, wio)  = TestUtils.signal
        val discardedWio = WIO.Discarded(wio, TestState(Nil, Nil))
        GetSignalDefsEvaluator.run(discardedWio).shouldBe(empty)
      }
    }

    "for an AndThen structure" - {
      "should only return the signal from the first, un-executed step" in {
        val (signalDef1, _, step1) = TestUtils.signal
        val (_, _, step2)          = TestUtils.signal
        val wio                    = step1 >>> step2
        GetSignalDefsEvaluator.run(wio).should(contain).theSameElementsAs(List(signalDef1))
      }

      "should return the signal from the second step if the first is executed" in {
        val (signalDef1, _, step1) = TestUtils.signal
        val (signalDef2, _, step2) = TestUtils.signal
        val executedStep1          = WIO.Executed(step1, Right(TestState(Nil, Nil)), TestState(Nil, Nil), 1)
        val wio                    = executedStep1 >>> step2
        GetSignalDefsEvaluator.run(wio).should(contain).theSameElementsAs(List(signalDef2))
      }
    }

    "for a FlatMap structure" - {
      "should return the signal from the base" in {
        val (signalDef, _, base) = TestUtils.signal
        val wio                  = base.flatMap(_ => TestCtx2.WIO.pure(TestState(Nil, Nil)).done)
        GetSignalDefsEvaluator.run(wio) should contain(signalDef)
      }
    }

    "for a Retry structure" - {
      "should return the signal from the base" in {
        val (signalDef, _, base) = TestUtils.signal
        val retryDelay           = Duration.ofSeconds(13)
        val wio                  = base.retryIn { case _ => retryDelay }
        GetSignalDefsEvaluator.run(wio) should contain(signalDef)
      }
    }

    "for a HandleErrorWith structure" - {
      "should return the signal from the base if the base has not failed" in {
        val (signalDef, _, base) = TestUtils.signal
        val handler              = TestCtx2.WIO.pure(TestState(Nil, Nil)).done
        val wio                  = base.handleErrorWith(handler)
        GetSignalDefsEvaluator.run(wio).should(contain).theSameElementsAs(List(signalDef))
      }

      "should return the signal from the handler if the base has failed" in {
        val (_, _, handleSignal) = TestUtils.signal
        val handler              = TestCtx2.WIO.pure(TestState(Nil, Nil)).done
        val base                 = WIO.Executed(handleSignal, Left("error"), TestState(Nil), 0)
        val wio                  = base.handleErrorWith(handler)
        GetSignalDefsEvaluator.run(wio).shouldBe(empty)
      }
    }
  }
}
