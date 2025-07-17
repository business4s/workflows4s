package workflows4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.{ModelUtils, WIOMeta}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

object DraftBuilder {
  private val draftSignal = SignalDef[Unit, Unit]()

  trait Step0[Ctx <: WorkflowContext]() {

    def draft: DraftBuilderStep1 = DraftBuilderStep1()

    class DraftBuilderStep1 {
      def signal(name: String = null, error: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx]                                  = WIO.HandleSignal(
        draftSignal,
        SignalHandler[Unit, Unit, Any]((_, _) => ???),
        dummyEventHandler,
        WIO.HandleSignal.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)),
          None,
        ),
      )
      def timer(name: String = null, duration: FiniteDuration = null)(using autoName: sourcecode.Name): WIO.Timer[Ctx, Any, Nothing, Nothing] =
        WIO.Timer(
          WIO.Timer.DurationSource.Static(duration.toJava), // TODO will NPE when rendering
          dummyEventHandler,
          Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)).some,
          dummyEventHandler,
        )

      def step(name: String = null, error: String = null, description: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = WIO.RunIO(
        _ => ???,
        dummyEventHandler,
        WIO.RunIO.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)).some,
          Option(description),
        ),
      )

      def forEach(forEach: WIO.Draft[Ctx], name: String = null)(using autoName: sourcecode.Name): WIO.Draft[Ctx] = {
        val effName = Option(name).getOrElse(ModelUtils.prettifyName(autoName.value)).some
        WIO.ForEach(_ => ???, forEach, () => ???, null, _ => ???, (_, _, _) => ???, (_, _) => ???, None, null, WIOMeta.ForEach(effName))
      }

      def repeat(conditionName: String = null, releaseBranchName: String = null, restartBranchName: String = null)(
          body: WIO.Draft[Ctx],
          onRestart: WIO.Draft[Ctx] = null,
      ): WIO.Draft[Ctx] = {
        val base: WIO[WCState[Ctx], Nothing, WCState[Ctx], Ctx] = Option(onRestart) match {
          case Some(_) => WIO.build[Ctx].repeat(body).until(_ => ???).onRestart(onRestart).named(conditionName, releaseBranchName, restartBranchName)
          case None    => WIO.build[Ctx].repeat(body).until(_ => ???).onRestartContinue.named(conditionName, releaseBranchName, restartBranchName)
        }
        base.transformInput((_: Any) => ???).map(_ => ???)
      }

    }

  }

  private def dummyEventHandler[EventBase, Evt]: EventHandler[Any, Nothing, EventBase, Evt] = EventHandler(_ => ???, _ => ???, (_, _) => ???)

}
