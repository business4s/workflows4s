package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.EitherValues
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.RunIO

import java.time.Instant

class WIORunIOTest extends AnyFreeSpec with Matchers with EitherValues {

  import TestCtx.*

  "WIO.RunIO" - {

    "proceed" in {
      val wf = WIO
        .runIO[String](input => IO.pure(s"ProcessedEvent($input)"))
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)

      assert(resultOpt.toRaw.isDefined)
      val newEvent = resultOpt.toRaw.get.unsafeRunSync().value
      assert(newEvent == SimpleEvent("ProcessedEvent(initialState)"))
    }

    "error in IO" in {
      val wf = WIO
        .runIO[String](_ => IO.raiseError(new RuntimeException("IO failed")))
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val Some(result) = wf.proceed(Instant.now).toRaw: @unchecked

      val Left(ex) = result.attempt.unsafeRunSync(): @unchecked
      assert(ex.getMessage == "IO failed")
    }

    "event handling" in {
      val wf = WIO
        .runIO[String](_ => ???)
        .handleEvent((input, evt) => s"SuccessHandled($input, $evt)")
        .done
        .toWorkflow("initialState")

      val Some(result) = wf.handleEvent("my-event"): @unchecked

      assert(result.staticState == "SuccessHandled(initialState, SimpleEvent(my-event))")
    }
    "handle signal " in {
      val wf = WIO
        .runIO[Any](_ => ???)
        .handleEvent(ignore)
        .done
        .toWorkflow("initialState")

      val resultOpt = wf.handleSignal(SignalDef[String, String]())("").toRaw

      assert(resultOpt.isEmpty)
    }

    "metadata attachment" - {
      val base = WIO
        .runIO[String](input => IO.pure(s"EventGenerated($input)"))
        .handleEvent(ignore)

      extension (x: WIO[?, ?, ?]) {
        def extractMeta: RunIO.Meta = x.asInstanceOf[workflows4s.wio.WIO.RunIO[?, ?, ?, ?, ?]].meta
      }

      "defaults" in {
        val wio = base.done

        val meta = wio.extractMeta
        assert(meta == RunIO.Meta(ErrorMeta.NoError(), None, None))
      }

      "explicitly named" in {
        val wio = base.named("ExplicitRunIO")

        val meta = wio.extractMeta
        assert(meta.name.contains("ExplicitRunIO"))
      }

      "autonamed" in {
        val autonamedRunIO = base.autoNamed()

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

}
