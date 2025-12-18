package workflows4s.wio

import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.WIO.HandleSignal

import java.time.Instant
import scala.util.Random
import workflows4s.wio.internal.GetSignalDefsEvaluator

class WIOHandleSignalTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.HandleSignal" - {

    "process valid signal" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => s"input: $input, request: $request")
        .handleEvent((input, request) => s"eventProcessed($input, $request)")
        .produceResponse((input, request) => s"response($input, $request)")
        .done
        .toWorkflow("initialState")

      // check SignalDef list
      GetSignalDefsEvaluator.run(wf.wio) should contain(mySignalDef)

      // Act
      val signalResult = wf.handleSignal(mySignalDef)(42).toRaw

      // Assert
      signalResult.`should`(not).`be`(empty)
      assert(
        signalResult.get === (
          SimpleEvent(
            "input: initialState, request: 42",
          ),
          "response(initialState, SimpleEvent(input: initialState, request: 42))",
        ),
      )
    }

    "handle unexpected signals gracefully" in {
      val validSignalDef      = SignalDef[Int, String]()
      val unexpectedSignalDef = SignalDef[String, String]()

      val wf: ActiveWorkflow[Eff, Ctx] = WIO
        .handleSignal(validSignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done
        .toWorkflow("initialState")

      GetSignalDefsEvaluator.run(wf.wio) should contain(validSignalDef)

      val unexpectedSignalResult = wf.handleSignal(unexpectedSignalDef)("unexpected").toRaw

      assert(unexpectedSignalResult.isEmpty)
    }

    "handle event" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent((input, request) => s"eventHandled($input, $request)")
        .produceResponse((input, request) => s"responseCrafted($input, $request)")
        .done
        .toWorkflow("initialState")

      val event          = "test-event"
      val newWorkflowOpt = wf.handleEvent(event)

      assert(newWorkflowOpt.isDefined)
      assert(newWorkflowOpt.get.staticState == "eventHandled(initialState, SimpleEvent(test-event))")
    }

    "handle event with error" in {
      import TestCtx2.*
      val error = Random.alphanumeric.take(10).mkString
      case class MyEvent(err: String) extends Event
      val signalHandler: WIO[Any, String, TestState] = WIO
        .handleSignal(mySignalDef)
        .using[Any]
        .purely((_, _) => MyEvent(error))
        .handleEventWithError((_, evt) => Left(evt.err))
        .produceResponse((_, _) => "")
        .done
      val eventHandler                               = TestUtils.errorHandler
      val wio                                        = signalHandler.handleErrorWith(eventHandler)
      val (_, wf)                                    = TestUtils.createInstance2(wio)

      wf.deliverSignal(mySignalDef, 1)
      assert(wf.queryState() == TestState(List(), List(error)))
    }

    "proceed should be a no-op" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)
      assert(resultOpt.toRaw.isEmpty)
    }

    "attach meta" - {
      lazy val base = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => s"metaSideEffect: $input/$request")
        .handleEvent(ignore)
        .produceResponse(ignore)
      extension (x: WIO[?, ?, ?]) {
        def extractMeta: HandleSignal.Meta = x.asInstanceOf[workflows4s.wio.WIO.HandleSignal[?, ?, ?, ?, ?, ?, ?, ?]].meta
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

    "convert to interruption" in {
      import TestCtx2.*
      val (_, _, wio)                             = TestUtils.signal
      val _: WIO.Interruption[Nothing, TestState] = wio.toInterruption
    }

  }

  val mySignalDef: SignalDef[Int, String] = SignalDef[Int, String]()
}
