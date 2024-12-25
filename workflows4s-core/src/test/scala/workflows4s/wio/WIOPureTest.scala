package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.{Pure, RunIO}

import java.time.Instant

class WIOPureTest extends AnyFreeSpec with Matchers {

  object TestCtx extends WorkflowContext {
    type Event = String
    type State = String
  }
  import TestCtx.*

  "WIO.Pure" - {

    "state" in {
      val wio: WIO[Any, Nothing, String] = WIO.pure("myValue").done
      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val state = activeWorkflow.liveState(Instant.now)

      assert(state == "myValue")
    }

      // TODO
//    "error" in {
//      val wio: WIO[Any, String, Nothing] = WIO.pure.error("Error")
//
//      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)
//
//      val Some(result) = activeWorkflow.proceed(Instant.now)
//
//      val Left(ex) = result.attempt.unsafeRunSync()
//      assert(ex.getMessage == "IO failed")
//    }

    "proceed no-op" in {
      val wio: WIO[Any, Nothing, String] = WIO.pure("myValue").done
      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val resultOpt = activeWorkflow.proceed(Instant.now)

      assert(resultOpt.isEmpty)
    }

    "event handling no-op" in {
      val wio: WIO[Any, Nothing, String] = WIO.pure("myValue").done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialInput", None)

      val resultOpt = activeWorkflow.handleEvent("my-event", Instant.now)

      assert(resultOpt.isEmpty)
    }

    "handle signal no-op" in {
      val wio: WIO[Any, Nothing, String] = WIO.pure("initialState").done

      val activeWorkflow = ActiveWorkflow[Ctx, String](wio, "initialState", None)

      val resultOpt = activeWorkflow.handleSignal(SignalDef[String, String]())("", Instant.now)
      assert(resultOpt.isEmpty)
    }

    "metadata attachment" - {
      val base = WIO.pure[String]("initialState")

      extension (x: WIO[?, ?, ?]) {
        def extractMeta: Pure.Meta = x.asInstanceOf[workflows4s.wio.WIO.Pure[?, ?, ?, ?]].meta
      }

      "defaults" in {
        val wio = base.done

        val meta = wio.extractMeta
        assert(meta == Pure.Meta(ErrorMeta.NoError(), None))
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
        val wio = WIO.pure.error("String").done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("String"))
      }
      "error explicitly named" in {
        val wio = WIO.pure.error("String")(using ErrorMeta.Present("custom")).done

        val meta = wio.extractMeta
        assert(meta.error == ErrorMeta.Present("custom"))
      }
    }
  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???
}
