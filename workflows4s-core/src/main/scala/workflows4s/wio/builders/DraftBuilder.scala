package workflows4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.internal.{EventHandler, SignalHandler}
import workflows4s.wio.model.{ModelUtils, WIOMeta}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

trait DraftBuilderStep0[F[_], Ctx <: WorkflowContext] {
  import DraftBuilder.*

  val draft: DraftBuilderStep1.type = DraftBuilderStep1

  object DraftBuilderStep1 {
    def signal(name: String = null, error: String = null)(using autoName: sourcecode.Name): WIO.Draft[F, Ctx]                                  =
      WIO.HandleSignal[F, Ctx, Any, Nothing, Nothing, Unit, Unit, Unit](
        draftSignal,
        SignalHandler[F, Unit, Unit, Any]((_, _) => ???),
        dummyEventHandler,
        WIO.HandleSignal.Meta(
          Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
          getEffectiveName(name, autoName),
          None,
        ),
      )
    def timer(name: String = null, duration: FiniteDuration = null)(using autoName: sourcecode.Name): WIO.Timer[F, Ctx, Any, Nothing, Nothing] =
      WIO.Timer(
        Option(duration) match {
          case Some(value) => WIO.Timer.DurationSource.Static(value.toJava)
          case None        => WIO.Timer.DurationSource.Dynamic(_ => ???)
        },
        dummyEventHandler,
        getEffectiveName(name, autoName).some,
        dummyEventHandler,
      )

    def step(name: String = null, error: String = null, description: String = null)(using autoName: sourcecode.Name): WIO.Draft[F, Ctx] = WIO.RunIO(
      _ => ???,
      dummyEventHandler,
      WIO.RunIO.Meta(
        Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
        getEffectiveName(name, autoName).some,
        Option(description),
      ),
    )

    def choice(name: String = null)(branches: (String, WIO.Draft[F, Ctx])*)(using autoName: sourcecode.Name): WIO.Draft[F, Ctx] = {
      val branchWios = branches.map { case (branchName, wio) =>
        WIO.Branch[F, Any, Nothing, Nothing, Ctx, Any](_ => None, wio, Some(branchName))
      }
      WIO.Fork(branchWios.toVector, getEffectiveName(name, autoName).some, None)
    }

    def forEach(forEach: WIO.Draft[F, Ctx], name: String = null)(using autoName: sourcecode.Name): WIO.Draft[F, Ctx] = {
      val effName = getEffectiveName(name, autoName).some
      WIO.ForEach(_ => ???, forEach, () => ???, null, (_, _) => ???, (_, _) => ???, None, null, WIOMeta.ForEach(effName))
    }

    def repeat(conditionName: String = null, releaseBranchName: String = null, restartBranchName: String = null)(
        body: WIO.Draft[F, Ctx],
        onRestart: WIO.Draft[F, Ctx] = null,
    ): WIO.Draft[F, Ctx] = {
      val base: WIO[F, WCState[Ctx], Nothing, WCState[Ctx], Ctx] = Option(onRestart) match {
        case Some(_) =>
          WIO.build[F, Ctx].repeat(body).until(_ => ???).onRestart(onRestart).named(conditionName, releaseBranchName, restartBranchName)
        case None    => WIO.build[F, Ctx].repeat(body).until(_ => ???).onRestartContinue.named(conditionName, releaseBranchName, restartBranchName)
      }
      base.transformInput((_: Any) => ???).map(_ => ???)
    }

    def parallel(elements: WIO.Draft[F, Ctx]*): WIO.Draft[F, Ctx] = {
      val parallelElements = elements.map { element =>
        WIO.Parallel.Element(element.map(_ => ???), (interimState: WCState[Ctx], _: WCState[Ctx]) => interimState)
      }
      WIO
        .Parallel[F, Ctx, Any, Nothing, WCState[Ctx], WCState[Ctx]](
          elements = parallelElements,
          formResult = _ => ???,
          initialInterimState = (_: Any) => ???,
        )
        .transformInput((_: Any) => ???)
        .map(_ => ???)
    }

    def recovery: WIO.Draft[F, Ctx] = WIO.Recovery(dummyEventHandler)

    def interruptionSignal(
        signalName: String = null,
        operationName: String = null,
        error: String = null,
    )(using autoName: sourcecode.Name): WIO.Interruption[F, Ctx, Nothing, Nothing] = {
      val draftSignalHandling = WIO
        .HandleSignal[F, Ctx, WCState[Ctx], Nothing, Nothing, Unit, Unit, Unit](
          draftSignal,
          SignalHandler[F, Unit, Unit, WCState[Ctx]]((_, _) => ???),
          dummyEventHandler[WCEvent[Ctx], Unit],
          WIO.HandleSignal.Meta(
            Option(error).map(ErrorMeta.Present(_)).getOrElse(ErrorMeta.noError),
            Option(signalName).getOrElse(getEffectiveName(null, autoName)),
            Option(operationName),
          ),
        )
        .transformInput((_: WCState[Ctx]) => ???)
        .map(_ => ???)
      WIO.Interruption(draftSignalHandling, WIO.HandleInterruption.InterruptionType.Signal)
    }

    def interruptionTimeout(
        timerName: String = null,
        duration: FiniteDuration = null,
    )(using autoName: sourcecode.Name): WIO.Interruption[F, Ctx, Nothing, Nothing] = {
      val draftTimer = WIO
        .Timer[F, Ctx, WCState[Ctx], Nothing, WCState[Ctx]](
          Option(duration) match {
            case Some(value) => WIO.Timer.DurationSource.Static(value.toJava)
            case None        => WIO.Timer.DurationSource.Dynamic(_ => ???)
          },
          dummyEventHandler[WCEvent[Ctx], WIO.Timer.Started],
          Option(timerName).orElse(getEffectiveName(null, autoName).some),
          dummyEventHandler[WCEvent[Ctx], WIO.Timer.Released],
        )
        .transformInput((_: WCState[Ctx]) => ???)
        .map(_ => ???)
      WIO.Interruption(draftTimer, WIO.HandleInterruption.InterruptionType.Timer)
    }

    def retry(base: WIO.Draft[F, Ctx]): WIO.Draft[F, Ctx]      = {
      WIO
        .Retry(
          base,
          (_: Throwable, _: WCState[Ctx], _: java.time.Instant) => ???,
        )
        .transformInput((_: Any) => ???)
        .map(_ => ???)
    }
    def checkpoint(base: WIO.Draft[F, Ctx]): WIO.Draft[F, Ctx] = WIO.Checkpoint(base, (_, _) => ???, dummyEventHandler)

    object syntax {
      extension (base: WIO.Draft[F, Ctx]) {
        def draftCheckpointed: WIO.Draft[F, Ctx] = checkpoint(base)
        def draftRetry: WIO.Draft[F, Ctx]        = retry(base)
      }
    }

  }

}

object DraftBuilder {
  private[builders] val draftSignal = SignalDef[Unit, Unit]()

  private[builders] def dummyEventHandler[EventBase, Evt]: EventHandler[Any, Nothing, EventBase, Evt] =
    EventHandler(_ => ???, _ => ???, (_, _) => ???)

  private[builders] def getEffectiveName(name: String, autoName: sourcecode.Name): String =
    Option(name).getOrElse(ModelUtils.prettifyName(autoName.value))

  type Step0[F[_], Ctx <: WorkflowContext] = DraftBuilderStep0[F, Ctx]
}
