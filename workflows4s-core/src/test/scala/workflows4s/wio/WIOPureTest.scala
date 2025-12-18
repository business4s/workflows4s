package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.WIO.Pure

import java.time.Instant
import scala.util.Random

class WIOPureTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.Pure" - {

    "state" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val state = wf.liveState

      assert(state == "myValue")
    }

    "proceed no-op" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val resultOpt = wf.proceed(Instant.now)

      assert(resultOpt.toRaw.isEmpty)
    }

    "event handling no-op" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO.pure("myValue").done.toWorkflow("initialState")

      val resultOpt = wf.handleEvent("my-event")

      assert(resultOpt.isEmpty)
    }

    "handle signal no-op" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO.pure("initialState").done.toWorkflow("initialState")

      val resultOpt = wf.handleSignal(SignalDef[String, String]())("").toRaw
      assert(resultOpt.isEmpty)
    }

    "error raising" in {
      import TestCtx2.*
      val error   = Random.alphanumeric.take(10).mkString
      val pure    = WIO.pure.makeFrom[TestState].error(_ => error).done
      val handler = TestUtils.errorHandler
      val wio     = pure.handleErrorWith(handler)
      val (_, wf) = TestUtils.createInstance2(wio)
      assert(wf.queryState() == TestState(List(), List(error)))
    }

    "metadata attachment" - {
      val base = WIO.pure("initialState")

      extension (x: WIO[?, ?, ?]) {
        def extractMeta: Pure.Meta = x.asInstanceOf[workflows4s.wio.WIO.Pure[?, ?, ?, ?, ?]].meta
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
