package workflows4s.wio

import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIOAndThenTest extends AnyFreeSpec with Matchers with EitherValues {

  import TestCtx.*

  "WIO.AndThen" - {

    "simple" in {
      val (step1Id, step1) = TestUtils.pure
      val (step2Id, step2) = TestUtils.pure

      val (_, wf) = TestUtils.createInstance2(step1 >>> step2)
      assert(wf.queryState().executed == List(step1Id, step2Id))
    }

    "handle signal" - {
      "handle on first" in {
        val (signalDef, stepId1, step1) = TestUtils.signal
        val (step2Id, step2) = TestUtils.pure

        val (_, wf) = TestUtils.createInstance2(step1 >>> step2)

        assert(wf.queryState().executed == List())

        val resp = wf.deliverSignal(signalDef, 1).value
        assert(resp == 1)

        assert(wf.queryState().executed == List(stepId1, step2Id))
      }
      "handle on second" in {
        val (step1Id, step1) = TestUtils.pure
        val (signalDef, stepId2, step2) = TestUtils.signal

        val (_, wf) = TestUtils.createInstance2(step1 >>> step2)
        assert(wf.queryState().executed == List(step1Id))

        val resp = wf.deliverSignal(signalDef, 1).value
        assert(resp == 1)

        assert(wf.queryState().executed == List(step1Id, stepId2))
      }
    }
    "proceed" - {
      "handle on first" in {
        val (step1Id, runIO) = TestUtils.runIO
        val (step2Id, step2) = TestUtils.pure

        val (_, wf) = TestUtils.createInstance2(runIO >>> step2)
        assert(wf.queryState().executed == List())

        wf.wakeup()
        assert(wf.queryState().executed == List(step1Id, step2Id))
      }
      "handle in sequence" in {
        val (step1Id, runIO1) = TestUtils.runIO
        val (step2Id, runIO2) = TestUtils.runIO

        val (_, wf) = TestUtils.createInstance2(runIO1 >>> runIO2)
        assert(wf.queryState().executed == List())

        wf.wakeup()
        assert(wf.queryState().executed == List(step1Id, step2Id))
      }
    }
  }
}
