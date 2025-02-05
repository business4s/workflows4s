package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class WIOAndThenTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.AndThen" - {

    "simple" in {
      val a     = WIO.pure.makeFrom[String].value(_ + "A").done
      val b     = WIO.pure.makeFrom[String].value(_ + "B").done
      val wf    = (a >>> b).toWorkflow("0")
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

        val wf = (handleSignal >>> WIO.end).toWorkflow("initialState")

        val Some(eventIO) = wf.handleSignal(mySignalDef)(42, Instant.now): @unchecked

        assert(
          eventIO.unsafeRunSync() == (SimpleEvent(
            "input: initialState, request: 42",
          ), "response(initialState, SimpleEvent(input: initialState, request: 42))"),
        )
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

        val wf = (pure >>> handleSignal).toWorkflow("initialState")

        val Some(eventIO) = wf.handleSignal(mySignalDef)(42, Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == (SimpleEvent("input: A, request: 42"), "response(A, SimpleEvent(input: A, request: 42))"))
      }
    }
    "proceed" - {
      "handle on first" in {
        val runIO = WIO
          .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
          .handleEvent(ignore)
          .done

        val wf = (runIO >>> WIO.end).toWorkflow("initialState")

        val Some(eventIO) = wf.proceed(Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == SimpleEvent("ProcessedEvent(initialState)"))
      }
      "handle on second" in {
        val runIO = WIO
          .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
          .handleEvent(ignore)
          .done

        val wf = (WIO.pure("A").done >>> runIO).toWorkflow("")

        val Some(eventIO) = wf.proceed(Instant.now): @unchecked

        assert(eventIO.unsafeRunSync() == SimpleEvent("ProcessedEvent(A)"))
      }
    }
    "handle event" - {
      "handle on first" in {
        val runIO = WIO
          .runIO[String](_ => ???)
          .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
          .done

        val wf = (runIO >>> WIO.end).toWorkflow("initialState")

        val Some(result) = wf.handleEvent("my-event", Instant.now): @unchecked

        assert(result.staticState == "SuccessHandled(initialState, SimpleEvent(my-event))")
      }
      "handle on second" in {
        val runIO = WIO
          .runIO[String](_ => ???)
          .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
          .done

        val wf = (WIO.pure("A").done >>> runIO).toWorkflow("")

        val Some(result) = wf.handleEvent("my-event", Instant.now): @unchecked

        assert(result.staticState == "SuccessHandled(A, SimpleEvent(my-event))")
      }
    }

  }
}
