package workflows4s.wio

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class WIOFlatMapTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.FlatMap" - {

    "simple" in {
      val a     = WIO.pure.makeFrom[String].value(_ + "A").done
      val b     = WIO.pure.makeFrom[String].value(_ + "B").done
      val wf    = a
        .flatMap(_ => b)
        .toWorkflow("0")
      val state = wf.liveState(Instant.now)
      assert(state == "0AB")
    }

    "handle signal" - {
      "handle on first" in {
        val mySignalDef  = SignalDef[Int, String]()
        val handleSignal = WIO
          .handleSignal(mySignalDef)
          .using[String]
          .withSideEffects((input, request) => IO(s"input: $input, request: $request"))
          .handleEvent((input, request) => s"eventProcessed($input, $request)")
          .produceResponse((input, request) => s"response($input, $request)")
          .done

        val wf = handleSignal.flatMap(_ => WIO.end).toWorkflow("initialState")

        val Some(eventIO) = wf.handleSignal(mySignalDef)(42, Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == ("input: initialState, request: 42", "response(initialState, input: initialState, request: 42)"))
      }
      "handle on second" in {
        val mySignalDef  = SignalDef[Int, String]()
        val handleSignal = WIO
          .handleSignal(mySignalDef)
          .using[String]
          .withSideEffects((input, request) => IO(s"input: $input, request: $request"))
          .handleEvent((input, request) => s"eventProcessed($input, $request)")
          .produceResponse((input, request) => s"response($input, $request)")
          .done
        val pure         = WIO.pure("A").done

        val wf = pure.flatMap(_ => handleSignal).toWorkflow("initialState")

        val Some(eventIO) = wf.handleSignal(mySignalDef)(42, Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == ("input: A, request: 42", "response(A, input: A, request: 42)"))
      }
    }
    "proceed" - {
      "handle on first" in {
        val runIO = WIO
          .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
          .handleEvent(ignore)
          .done

        val wf = runIO.flatMap(_ => WIO.end).toWorkflow("initialState")

        val Some(eventIO) = wf.proceed(Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == "ProcessedEvent(initialState)")
      }
      "handle on second" in {
        val runIO = WIO
          .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
          .handleEvent(ignore)
          .done

        val wf = WIO.pure("A").done.flatMap(_ => runIO).toWorkflow("")

        val Some(eventIO) = wf.proceed(Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == "ProcessedEvent(A)")
      }
    }
    "handle event" - {
      "handle on first" in {
        val runIO = WIO
          .runIO[String](_ => ???)
          .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
          .done

        val wf = runIO.flatMap(_ => WIO.end).toWorkflow("initialState")

        val Some(result) = wf.handleEvent("my-event", Instant.now): @unchecked

        assert(result.staticState == "SuccessHandled(initialState, my-event)")
      }
      "handle on second" in {
        val runIO = WIO
          .runIO[String](_ => ???)
          .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
          .done

        val wf = WIO.pure("A").done.flatMap(_ => runIO).toWorkflow("")

        val Some(result) = wf.handleEvent("my-event", Instant.now): @unchecked

        assert(result.staticState == "SuccessHandled(A, my-event)")
      }
    }

  }
}
