package workflows4s.wio

import cats.effect.IO
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

import java.time.Instant
import scala.concurrent.duration.DurationInt
import cats.effect.unsafe.implicits.global

class WIOHandleInterruptionTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  import TestCtx.*

  val signalA: SignalDef[Int, String] = SignalDef[Int, String]()
  case class SignalAReceived(str: String) extends Event
  val handleSignalA        = WIO
    .handleSignal(signalA)
    .using[Any]
    .withSideEffects((input, request) => IO(SignalAReceived(s"$input,$request")))
    .handleEvent((input, request) => s"signalAOutput($input, $request)")
    .produceResponse((input, request) => s"signalAResponse($input, $request)")
    .done
  val interruptWithSignalA = WIO.interruption
    .throughSignal(signalA)
    .handleAsync((input, request) => IO(SignalAReceived(s"$input,$request)")))
    .handleEvent((input, request) => s"signalAOutput($input, $request)")
    .produceResponse((input, request) => s"signalAResponse($input, $request)")
    .done

  "WIO.HandleInterruption" - {

    "timer interruption" - {
      case class TimerStarted(at: Instant)  extends TestCtx.Event
      case class TimerReleased(at: Instant) extends TestCtx.Event
      val await20Sec = WIO.interruption
        .throughTimeout(20.seconds)
        .persistStartThrough(x => TimerStarted(x.at))(_.at)
        .persistReleaseThrough(x => TimerReleased(x.at))(_.at)
        .done

      "trigger interruption" in {
        val base         = handleSignalA
        val interruption = await20Sec.andThen(_ >>> WIO.pure("B").done)

        val wf                = base.interruptWith(interruption)
        val (clock, instance) = TestUtils.createInstance(wf)
        val startTime         = clock.instant()

        instance.wakeup()
        clock.advanceBy(21.seconds)
        instance.wakeup()

        assert(instance.queryState() === "B")
        assert(instance.getEvents === List(TimerStarted(startTime), TimerReleased(startTime.plusSeconds(21))))
      }

      "proceed on base" in {
        val base         = handleSignalA
        val interruption = await20Sec.andThen(_ >>> WIO.pure("B").done)

        val wf                = base.interruptWith(interruption)
        val (clock, instance) = TestUtils.createInstance(wf)
        val startTime         = clock.instant()

        instance.wakeup()
        val resp = instance.deliverSignal(signalA, 43).value

        assert(resp === "signalAResponse(initialState, SignalAReceived(initialState,43))")

        assert(instance.queryState() === "signalAOutput(initialState, SignalAReceived(initialState,43))")
        assert(
          instance.getEvents === List(
            TimerStarted(startTime),
            SignalAReceived("initialState,43"),
          ),
        )
      }

    }

    "signal interruption" - {

      val signalB: SignalDef[Int, String] = SignalDef[Int, String]()
      case class SignalBReceived(str: String) extends Event
      val handleSignalB = WIO
        .handleSignal(signalB)
        .using[String]
        .withSideEffects((input, request) => IO(SignalBReceived(s"$input,$request")))
        .handleEvent((input, request) => s"signalBOutput($input, $request)")
        .produceResponse((input, request) => s"signalBResponse($input, $request)")
        .done

      "initial state" in {
        val initialWIO = WIO.pure.makeFrom[String].value(_ + "MainFlow").done

        val wf = initialWIO.interruptWith(interruptWithSignalA).toWorkflow("initialState")

        val state = wf.liveState(Instant.now)
        assert(state === "initialStateMainFlow")
      }

      "effectful proceed on base" in {
        case class MyEvent(value: String) extends Event

        val initialWIO = WIO
          .runIO[String](input => IO(MyEvent(s"RunIOEvent($input)")))
          .handleEvent((input, event) => s"RunIOOutput($input, $event)")
          .done

        val wf = initialWIO.interruptWith(interruptWithSignalA).toWorkflow("initialState")

        val event = wf.proceed(Instant.now).value.unsafeRunSync().value

        assert(event === MyEvent("RunIOEvent(initialState)"))

        val nextWf = wf.handleEvent(event, Instant.now).value

        // after processing, state is coming from base flow
        val state = nextWf.liveState(Instant.now)
        assert(state === "RunIOOutput(initialState, MyEvent(RunIOEvent(initialState)))")

        // after base is processed, interruption is no longer possible
        val interruptionSignalResult = nextWf.handleSignal(signalA)(43, Instant.now)
        assert(interruptionSignalResult === None)

      }

      "handle signal - base" in {
        val wf           = handleSignalB.interruptWith(interruptWithSignalA).toWorkflow("initialState")
        val signalResult = wf.handleSignal(signalB)(42, Instant.now).value.unsafeRunSync()

        assert(
          signalResult === (
            SignalBReceived("initialState,42"),
            "signalBResponse(initialState, SignalBReceived(initialState,42))",
          ),
        )

        val Some(nextWf) = wf.handleEvent(signalResult._1, Instant.now): @unchecked

        // after processing, state is coming from base flow
        val state = nextWf.liveState(Instant.now)
        assert(state === "signalBOutput(initialState, SignalBReceived(initialState,42))")

        // after base is processed, interruption is no longer possible
        val interruptionSignalResult = nextWf.handleSignal(signalA)(43, Instant.now)
        assert(interruptionSignalResult === None)
      }

      "handle signal - interruption" in {
        val wf = handleSignalB.interruptWith(interruptWithSignalA).toWorkflow("initialState")

        val signalResult = wf.handleSignal(signalA)(42, Instant.now).value.unsafeRunSync()

        assert(
          signalResult === (
            SignalAReceived("initialState,42)"),
            "signalAResponse(initialState, SignalAReceived(initialState,42)))",
          ),
        )

        val Some(nextWf) = wf.handleEvent(signalResult._1, Instant.now): @unchecked

        // after processing, state is coming from interruption flow
        val state = nextWf.liveState(Instant.now)
        assert(state === "signalAOutput(initialState, SignalAReceived(initialState,42)))")

        // after base is processed, interruption is no longer possible
        val interruptionSignalResult = nextWf.handleSignal(signalB)(43, Instant.now)
        assert(interruptionSignalResult === None)
      }

      // TODO we could add more tests
      //  - proceed is evaluated on interruption flow after it was entered
      //  - base proceeed is not available while interruption flow was entered but not completed
      //  - base proceeed is not available while interruption flow was completed
    }

  }
}
