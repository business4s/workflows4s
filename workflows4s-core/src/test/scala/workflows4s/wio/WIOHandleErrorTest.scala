package workflows4s.wio

import cats.effect.IO
import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIOHandleErrorTest extends AnyFreeSpec with Matchers with EitherValues {

  import TestCtx.*

  case class MyError(value: Int)

  "WIO.HandleError" - {

    "from pure" in {
      val base    = WIO.pure("someState").done >>> WIO.pure.error(MyError(1)).done
      val handler = WIO.pure.makeFrom[(String, MyError)].value((state, error) => s"handlerResult($state, $error)").done
      val wio     = base.handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance(wio)

      assert(wf.queryState() === "handlerResult(someState, MyError(1))")
    }

    "from proceed" in {
      val base    = WIO.pure("someState").done >>> WIO.runIO[Any](in => IO(s"baseEvent($in)")).handleEventWithError((_, _) => Left(MyError(1))).done
      val handler = WIO.runIO[(String, MyError)](in => IO(s"handlerEvent($in)")).handleEvent((in, event) => s"handlerOut($in, $event)").done
      val wio     = base.handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance(wio)

      wf.wakeup()
      assert(wf.queryState() === "handlerOut((someState,MyError(1)), SimpleEvent(handlerEvent((someState,MyError(1)))))")
    }

    "from signal" in {
      val signal1 = SignalDef[Int, String]()
      val signal2 = SignalDef[Int, String]()
      val base    = WIO.pure("someState").done >>> WIO
        .handleSignal(signal1)
        .using[Any]
        .withSideEffects((in, req) => IO(s"baseEvent($in, $req)"))
        .handleEventWithError((_, _) => Left(MyError(1)))
        .produceResponse((in, req) => s"baseResponse($in, $req)")
        .done >>> WIO.pure("otherState").done
      val handler = WIO
        .handleSignal(signal2)
        .using[Any]
        .withSideEffects((in, req) => IO(s"handlerEvent($in, $req)"))
        .handleEvent((in, event) => s"handlerOutput($in, $event)")
        .produceResponse((in, req) => s"handlerResponse($in, $req)")
        .done
      val wio     = base.handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance(wio)

      val response = wf.deliverSignal(signal1, 43).value
      assert(response === "baseResponse(someState, SimpleEvent(baseEvent(someState, 43)))")
      assert(wf.queryState() === "someState")

      val response2 = wf.deliverSignal(signal2, 44).value
      assert(response2 === "handlerResponse((someState,MyError(1)), SimpleEvent(handlerEvent((someState,MyError(1)), 44)))")
      assert(wf.queryState() === "handlerOutput((someState,MyError(1)), SimpleEvent(handlerEvent((someState,MyError(1)), 44)))")
    }

  }

}
