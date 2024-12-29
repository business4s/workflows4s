package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.wio.WIO.Pure

import java.time.Instant

class WIOPureTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.Pure" - {

    "state" in {
      val  wf: ActiveWorkflow[Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val state = wf.liveState(Instant.now)

      assert(state == "myValue")
    }

    // TODO error case

    "proceed no-op" in {
      val wf: ActiveWorkflow[Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)

      assert(resultOpt.isEmpty)
    }

    "event handling no-op" in {
      val wf: ActiveWorkflow[Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val resultOpt = wf.handleEvent("my-event", Instant.now)

      assert(resultOpt.isEmpty)
    }

    "handle signal no-op" in {
      val wf: ActiveWorkflow[Ctx] = WIO.pure("initialState").done.toWorkflow("initialState")

      val resultOpt = wf.handleSignal(SignalDef[String, String]())("", Instant.now)
      assert(resultOpt.isEmpty)
    }

    "metadata attachment" - {
      val base = WIO.pure("initialState")

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

}
