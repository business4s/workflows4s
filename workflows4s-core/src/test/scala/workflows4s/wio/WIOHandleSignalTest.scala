package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.HandleSignal

import java.time.Instant

class WIOHandleSignalTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.HandleSignal" - {

    "process valid signal" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => IO(s"input: $input, request: $request"))
        .handleEvent((input, request) => s"eventProcessed($input, $request)")
        .produceResponse((input, request) => s"response($input, $request)")
        .done
        .toWorkflow("initialState")

      // Act
      val signalResult = wf.handleSignal(mySignalDef)(42, Instant.now)

      // Assert
      signalResult should not be empty
      assert(signalResult.get.unsafeRunSync() === ("input: initialState, request: 42", "response(initialState, input: initialState, request: 42)"))
    }

    "handle unexpected signals gracefully" in {
      val validSignalDef      = SignalDef[Int, String]()
      val unexpectedSignalDef = SignalDef[String, String]()

      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(validSignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done
        .toWorkflow("initialState")

      val unexpectedSignalResult = wf.handleSignal(unexpectedSignalDef)("unexpected", Instant.now)

      assert(unexpectedSignalResult.isEmpty)
    }

    "handle event" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent((input, request) => s"eventHandled($input, $request)")
        .produceResponse((input, request) => s"responseCrafted($input, $request)")
        .done
        .toWorkflow("initialState")

      val event          = "test-event"
      val newWorkflowOpt = wf.handleEvent(event, Instant.now)

      assert(newWorkflowOpt.isDefined)
      assert(newWorkflowOpt.get.staticState == "eventHandled(initialState, test-event)")
    }

    // TODO event with error case

    "proceed should be a no-op" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)
      assert(resultOpt.isEmpty)
    }

    "attach meta" - {
      lazy val base = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => IO(s"metaSideEffect: $input/$request"))
        .handleEvent(ignore)
        .produceResponse(ignore)
      extension (x: WIO[?, ?, ?]) {
        def extractMeta: HandleSignal.Meta = x.asInstanceOf[workflows4s.wio.WIO.HandleSignal[?, ?, ?, ?, ?, ?, ?]].meta
      }
      "defaults" in {
        val wio = base.done

        val meta = wio.extractMeta
        assert(meta == HandleSignal.Meta(ErrorMeta.NoError(), "Int", None))
      }

      "locally named signal" in {
        val wio = base.named(signalName = "explicitSignalName")

        val meta = wio.extractMeta
        assert(meta.signalName == "explicitSignalName")
      }
      "globally named signal" in {
        val namedSignal = SignalDef[String, String](name = "explicitSignalName")
        val wio         = WIO
          .handleSignal(namedSignal)
          .using[String]
          .withSideEffects(ignore)
          .handleEvent(ignore)
          .produceResponse(ignore)
          .done

        val meta = wio.extractMeta
        assert(meta.signalName == "explicitSignalName")
      }

      "autonamed step" in {
        val myName = base.autoNamed // Automatically derives a name from the code
        val meta   = myName.extractMeta
        assert(meta.operationName == "My Name".some)
      }

      "explicitly named step" in {
        val wio = base.named(operationName = "explicitOperationName")

        val meta = wio.extractMeta
        assert(meta.operationName == "explicitOperationName".some)
      }

      "with error autonamed" in {
        val wio = WIO
          .handleSignal(mySignalDef)
          .using[String]
          .withSideEffects(ignore)
          .handleEventWithError((_, _) => Left("errorOccurred"))
          .produceResponse(ignore)
          .autoNamed

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("String"))
      }

      "with error explicitly named" in {
        val errMeta = ErrorMeta.Present("errorOccurred")
        val wio     = WIO
          .handleSignal(mySignalDef)
          .using[String]
          .withSideEffects(ignore)
          .handleEventWithError(ignore)(using ErrorMeta.Present("errorOccurred"))
          .produceResponse(ignore)
          .done

        val meta = wio.extractMeta
        assert(meta.error == errMeta)
      }
    }

  }

  val mySignalDef: SignalDef[Int, String] = SignalDef[Int, String]()
}
