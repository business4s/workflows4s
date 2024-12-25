package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.RunIO

import java.time.Instant

class WIORunIOTest extends AnyFreeSpec with Matchers {

  object TestCtx extends WorkflowContext {
    type Event = String
    type State = String
  }
  import TestCtx.*

  "WIO.RunIO" - {

    "proceed" in {
      val wio: WIO[String, Nothing, String] = WIO
        .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
        .handleEvent(ignore)
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val resultOpt = activeWorkflow.proceed(Instant.now)

      assert(resultOpt.isDefined)
      val newEvent = resultOpt.get.unsafeRunSync()
      assert(newEvent == "ProcessedEvent(initialState)")
    }

    "error in IO" in {
      val wio: WIO[String, Nothing, String] = WIO
        .runIO[String](_ => IO.raiseError(new RuntimeException("IO failed")))
        .handleEvent(ignore)
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val Some(result) = activeWorkflow.proceed(Instant.now)

      val Left(ex) = result.attempt.unsafeRunSync()
      assert(ex.getMessage == "IO failed")
    }

    "event handling" in {
      val wio: WIO[String, Nothing, String] = WIO
        .runIO[String](_ => ???)
        .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialInput", None)

      val Some(result) = activeWorkflow.handleEvent("my-event", Instant.now)

      assert(result.state == "SuccessHandled(initialInput, my-event)")
    }
    "handle signal " in {
      val wio: WIO[Any, Nothing, String] = WIO
        .runIO[Any](_ => ???)
        .handleEvent(ignore)
        .done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val resultOpt = activeWorkflow.handleSignal(SignalDef[String, String]())("", Instant.now)

      assert(resultOpt.isEmpty)
    }

    "metadata attachment for RunIO" - {
      val base = WIO
        .runIO[String](input => IO.pure(s"EventGenerated($input)"))
        .handleEvent(ignore)

      extension (x: WIO[?, ?, ?]) {
        def extractMeta: RunIO.Meta = x.asInstanceOf[workflows4s.wio.WIO.RunIO[?, ?, ?, ?, ?]].meta
      }

      "defaults" in {
        val wio = base.done

        val meta = wio.extractMeta
        assert(meta == RunIO.Meta(ErrorMeta.NoError(), None))
      }

      "explicitly named" in {
        val wio = base.named("ExplicitRunIO")

        val meta = wio.extractMeta
        assert(meta.name.contains("ExplicitRunIO"))
      }

      "autonamed" in {
        val autonamedRunIO = base.autoNamed

        val meta = autonamedRunIO.extractMeta
        assert(meta.name.contains("Autonamed Run IO"))
      }

      "error autonamed" in {
        val wio = WIO
          .runIO[String](_ => ???)
          .handleEventWithError((_, _) => Left(""))
          .done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("String"))
      }
      "error explicitly named" in {
        val wio = WIO
          .runIO[String](_ => ???)
          .handleEventWithError(ignore)(using ErrorMeta.Present("XXX"))
          .done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("XXX"))
      }
    }
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???
}
