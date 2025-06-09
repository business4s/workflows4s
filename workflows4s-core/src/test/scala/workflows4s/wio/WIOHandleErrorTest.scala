package workflows4s.wio

import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIOHandleErrorTest extends AnyFreeSpec with Matchers with EitherValues {

  case class MyError(value: Int)

  "WIO.HandleError" - {

    "from pure" in {
      val (step1Id, step1) = TestUtils.pure
      val (error, step2)   = TestUtils.error
      val handler          = TestUtils.errorHandler

      val wio     = (step1 >>> step2).handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance2(wio)

      assert(wf.queryState() === TestState(executed = List(step1Id), errors = List(error)))
    }

    "from proceed" in {
      val (step1Id, step1) = TestUtils.runIO
      val (error, step2)   = TestUtils.error
      val handler          = TestUtils.errorHandler

      val wio     = (step1 >>> step2).handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance2(wio)

      wf.wakeup()
      assert(wf.queryState() === TestState(executed = List(step1Id), errors = List(error)))
    }

    "from signal" in {
      val (signal1, error, step1)   = TestUtils.signalError
      val (signal2, step2Id, step2) = TestUtils.signal
      val (_, step3)                = TestUtils.pure
      val base                      = step1 >>> step3
      val handler                   = step2.transformInput[(TestState, String)]((st, err) => st.addError(err))
      val wio                       = base.handleErrorWith(handler)
      val (_, wf)                   = TestUtils.createInstance2(wio)

      val response = wf.deliverSignal(signal1, 43).value
      assert(response === 43)
      assert(wf.queryState() === TestState.empty)

      val response2 = wf.deliverSignal(signal2, 44).value
      assert(response2 === 44)
      assert(wf.queryState() === TestState(executed = List(step2Id), errors = List(error)))
    }

  }

}
