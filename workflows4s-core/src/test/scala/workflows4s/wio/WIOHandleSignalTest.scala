package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import workflows4s.testing.TestUtils
import workflows4s.wio.WIO.HandleSignal

import java.time.Instant
import scala.util.Random
import workflows4s.wio.internal.GetSignalDefsEvaluator

class WIOHandleSignalTest extends AnyFreeSpec with Matchers with EitherValues {

  import TestCtx.*

  "WIO.HandleSignal" - {

    "process valid signal" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => IO(s"input: $input, request: $request"))
        .handleEvent((input, request) => s"eventProcessed($input, $request)")
        .produceResponse((input, evt, _) => s"response($input, $evt)")
        .done
        .toWorkflow("initialState")

      // check SignalDef list
      GetSignalDefsEvaluator.run(wf.wio) should contain(mySignalDef)

      // Act
      val signalResult = wf.handleSignal(mySignalDef)(42).toRaw

      // Assert
      signalResult should not be empty
      assert(
        signalResult.get.unsafeRunSync() === (
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

      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(validSignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore3)
        .done
        .toWorkflow("initialState")

      GetSignalDefsEvaluator.run(wf.wio) should contain(validSignalDef)

      val unexpectedSignalResult = wf.handleSignal(unexpectedSignalDef)("unexpected").toRaw

      assert(unexpectedSignalResult.isEmpty)
    }

    "handle event" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent((input, request) => s"eventHandled($input, $request)")
        .produceResponse((input, evt, _) => s"responseCrafted($input, $evt)")
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
        .produceResponse((_, _, _) => "")
        .done
      val eventHandler                               = TestUtils.errorHandler
      val wio                                        = signalHandler.handleErrorWith(eventHandler)
      val (_, wf)                                    = TestUtils.createInstance2(wio)

      wf.deliverSignal(mySignalDef, 1)
      assert(wf.queryState() == TestState(List(), List(error)))
    }

    "proceed should be a no-op" in {
      val wf: ActiveWorkflow[Ctx] = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects(ignore)
        .handleEvent(ignore)
        .produceResponse(ignore3)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)
      assert(resultOpt.toRaw.isEmpty)
    }

    "attach meta" - {
      lazy val base = WIO
        .handleSignal(mySignalDef)
        .using[String]
        .withSideEffects((input, request) => IO(s"metaSideEffect: $input/$request"))
        .handleEvent(ignore)
        .produceResponse(ignore3)
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
          .produceResponse(ignore3)
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
          .produceResponse(ignore3)
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
          .produceResponse(ignore3)
          .done

        val meta = wio.extractMeta
        assert(meta.error == errMeta)
      }
    }

    "convert to interruption" in {
      import TestCtx2.*
      val (_, _, wio: workflows4s.wio.WIO.IHandleSignal[TestState, Nothing, TestState, Ctx]) = TestUtils.signal
      val _: WIO.Interruption[Nothing, TestState]                                            = wio.toInterruption
    }

    "signal redelivery" - {
      "should return original response without re-running side effects" in {
        import TestCtx2.*
        var sideEffectCount = 0
        case class SignalEvent(req: Int) extends Event

        val signalDef = SignalDef[Int, Int]()
        val wio       = WIO
          .handleSignal(signalDef)
          .using[TestState]
          .withSideEffects { (_, req) =>
            sideEffectCount += 1
            IO.pure(SignalEvent(req))
          }
          .handleEvent((st, _) => st.addExecuted(StepId.random("signal")))
          .produceResponse((_, evt, _) => evt.req * 10) // Response based on stored event
          .done

        val (_, instance) = TestUtils.createInstance2(wio)

        // First delivery - side effect runs
        val response1 = instance.deliverSignal(signalDef, 42).value
        assert(response1 == 420)
        assert(sideEffectCount == 1)

        // Redelivery - side effect should NOT run again
        val response2 = instance.deliverSignal(signalDef, 999).value // Different request value
        assert(response2 == 420) // Still returns original response
        assert(sideEffectCount == 1) // Side effect count unchanged

        // State unchanged by redelivery
        val stateAfterRedelivery = instance.queryState()
        assert(stateAfterRedelivery.executed.size == 1)
      }

      "should work with response producer that has access to request" in {
        import TestCtx2.*
        case class SignalEvent(originalReq: Int) extends Event

        val signalDef = SignalDef[Int, String]()
        val wio       = WIO
          .handleSignal(signalDef)
          .using[TestState]
          .purely((_, req) => SignalEvent(req))
          .handleEvent((st, _) => st.addExecuted(StepId.random("signal")))
          .produceResponse { (_, evt, currentReq) =>
            // Response producer can compare original and current request
            s"original=${evt.originalReq}, current=$currentReq"
          }
          .done

        val (_, instance) = TestUtils.createInstance2(wio)

        // First delivery
        val response1 = instance.deliverSignal(signalDef, 42).value
        assert(response1 == "original=42, current=42")

        // Redelivery with same request
        val response2 = instance.deliverSignal(signalDef, 42).value
        assert(response2 == "original=42, current=42")

        // Redelivery with different request - response producer sees both values
        val response3 = instance.deliverSignal(signalDef, 99).value
        assert(response3 == "original=42, current=99")
      }
    }

  }

  val mySignalDef: SignalDef[Int, String] = SignalDef[Int, String]()
}
