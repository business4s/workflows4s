package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.HandleSignal

import java.time.Instant

class WIOHandleSignalTest extends AnyFreeSpec with Matchers {

  object TestCtx extends WorkflowContext {
    type Event = String
    type State = String
  }
  import TestCtx.*

  "WIO.HandleSignal" - {

    "process valid signal" in {
      val wio: WIO[String, Nothing, String] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => IO(s"input: $input, request: $request"))
        .handleEvent((input, request) => s"eventProcessed($input, $request)")
        .produceResponse((input, request) => s"response($input, $request)")
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      // Act
      val signalResult = activeWorkflow.handleSignal(mySignalDef)(42, Instant.now)

      // Assert
      signalResult should not be empty
      assert(signalResult.get.unsafeRunSync() === ("input: initialState, request: 42", "response(initialState, input: initialState, request: 42)"))
    }

    "handle unexpected signals gracefully" in {
      val validSignalDef      = SignalDef[Int, String]()
      val unexpectedSignalDef = SignalDef[String, String]()

      val wio: WIO[String, Nothing, String] = WIO
        .handleSignal(validSignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "state", None)

      val unexpectedSignalResult = activeWorkflow.handleSignal(unexpectedSignalDef)("unexpected", Instant.now)

      assert(unexpectedSignalResult.isEmpty)
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
        val myName = base.autoNamed() // Automatically derives a name from the code
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
          .autoNamed()

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

    "handle event" in {
      val wio: WIO[String, Nothing, String] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent((input, request) => s"eventHandled($input, $request)")
        .produceResponse((input, request) => s"responseCrafted($input, $request)")
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)
      val event          = "test-event"

      // Act
      val newWorkflowOpt = activeWorkflow.handleEvent(event, Instant.now)

      // Assert
      assert(newWorkflowOpt.isDefined)
      assert(newWorkflowOpt.get.staticState == "eventHandled(initialState, test-event)")
    }

    "proceed should be a no-op" in {
      val wio: WIO[String, Nothing, String] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val resultOpt = activeWorkflow.proceed(Instant.now)
      assert(resultOpt.isEmpty)
    }
  }

  val mySignalDef: SignalDef[Int, String] = SignalDef[Int, String]()
  def ignore[A, B, C]: (A, B) => C        = (_, _) => ???
}
