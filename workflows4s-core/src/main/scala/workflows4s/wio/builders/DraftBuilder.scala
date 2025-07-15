package workflows4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.ModelUtils
import workflows4s.wio.{ErrorMeta, SignalDef, WCEvent, WIO, WorkflowContext}

object DraftBuilder {
  private val draftSignal = SignalDef[Unit, Unit]()

  trait Step0[Ctx <: WorkflowContext]() {

    def draft: DraftBuilderStep1 = DraftBuilderStep1()

    class DraftBuilderStep1 {
      def signal(name: String = null, error: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = WIO.HandleSignal(
        draftSignal,
        SignalHandler[Unit, Unit, Any]((_, _) => ???),
        EventHandler[WCEvent[Ctx], Any, Nothing, Any](_ => ???, _ => ???, (_, _) => ???),
        WIO.HandleSignal.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)),
          None,
        ),
      )

      def step(name: String = null, error: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = WIO.RunIO(
        _ => ???,
        EventHandler[WCEvent[Ctx], Any, Nothing, Any](_ => ???, _ => ???, (_, _) => ???),
        WIO.RunIO.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)).some,
        ),
      )

      def forEach(forEach: WIO.Draft[Ctx]): WIO.Draft[Ctx] =
        WIO.ForEach(_ => ???, forEach, () => ???, null, _ => ???, (_, _, _) => ???, _ => ???, None, null)

    }

  }

}
