package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.WIO.HandleInterruption.InterruptionType
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.builders.AllBuilders
import workflows4s.wio.internal.{EventHandler, SignalHandler, WorkflowEmbedding}

import java.time.{Duration, Instant}
import scala.language.implicitConversions

sealed trait WIO[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] extends WIOMethods[Ctx, In, Err, Out]

object WIO {

  type Initial[Ctx <: WorkflowContext] = WIO[Any, Nothing, WCState[Ctx], Ctx]
  type Draft[Ctx <: WorkflowContext]   = WIO[Any, Nothing, Nothing, Ctx]

  // Experimental approach top exposing concrete subtypes.
  // We dont want to expose concrete impls because they have way too much type params.
  // Alternatively, this could be a sealed trait extending WIO
  type IHandleSignal[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] = HandleSignal[Ctx, In, Out, Err, ?, ?, ?]

  case class HandleSignal[Ctx <: WorkflowContext, -In, +Out <: WCState[Ctx], +Err, Sig, Resp, Evt](
      sigDef: SignalDef[Sig, Resp],
      sigHandler: SignalHandler[Sig, Evt, In],
      evtHandler: EventHandler[In, (Either[Err, Out], Resp), WCEvent[Ctx], Evt],
      meta: HandleSignal.Meta, // TODO here and everywhere else, we could use WIOMeta directly
  ) extends WIO[In, Err, Out, Ctx] {

    def toInterruption(using ev: WCState[Ctx] <:< In): Interruption[Ctx, Err, Out] =
      WIO.Interruption(ev.substituteContra[[t] =>> WIO[t, Err, Out, Ctx]](this), InterruptionType.Signal)
  }

  object HandleSignal {
    case class Meta(error: ErrorMeta[?], signalName: String, operationName: Option[String])
  }

  case class RunIO[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Evt](
      buildIO: In => IO[Evt],
      evtHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt],
      meta: RunIO.Meta,
  ) extends WIO[In, Err, Out, Ctx]

  object RunIO {
    case class Meta(error: ErrorMeta[?], name: Option[String])
  }

  case class Pure[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      value: In => Either[Err, Out],
      meta: Pure.Meta,
  ) extends WIO[In, Err, Out, Ctx]

  object Pure {
    case class Meta(error: ErrorMeta[?], name: Option[String])
  }

  case class Transform[Ctx <: WorkflowContext, In1, Err1, Out1 <: WCState[Ctx], -In2, +Out2 <: WCState[Ctx], +Err2](
      base: WIO[In1, Err1, Out1, Ctx],
      contramapInput: In2 => In1,
      mapOutput: (In2, Either[Err1, Out1]) => Either[Err2, Out2],
  ) extends WIO[In2, Err2, Out2, Ctx]

  case class End[Ctx <: WorkflowContext]() extends WIO[Any, Nothing, Nothing, Ctx]

  case class FlatMap[Ctx <: WorkflowContext, Err1 <: Err2, +Err2, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx], -In](
      base: WIO[In, Err1, Out1, Ctx],
      getNext: Out1 => WIO[Out1, Err2, Out2, Ctx],
      errorMeta: ErrorMeta[?],
  ) extends WIO[In, Err2, Out2, Ctx]

  case class HandleError[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], ErrIn, TempOut <: WCState[Ctx]](
      base: WIO[In, ErrIn, Out, Ctx],
      handleError: (WCState[Ctx], ErrIn) => WIO[Any, Err, Out, Ctx],
      handledErrorMeta: ErrorMeta[?],
      newErrorMeta: ErrorMeta[?],
  ) extends WIO[In, Err, Out, Ctx]

  case class HandleErrorWith[Ctx <: WorkflowContext, -In, Err, +Out <: WCState[Ctx], +ErrOut](
      base: WIO[In, Err, Out, Ctx],
      handleError: WIO[(WCState[Ctx], Err), ErrOut, Out, Ctx],
      handledErrorMeta: ErrorMeta[?],
      newErrorMeta: ErrorMeta[?],
  ) extends WIO[In, ErrOut, Out, Ctx]

  case class AndThen[Ctx <: WorkflowContext, -In, +Err, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx]](
      first: WIO[In, Err, Out1, Ctx],
      second: WIO[Out1, Err, Out2, Ctx],
  ) extends WIO[In, Err, Out2, Ctx]

  case class Loop[Ctx <: WorkflowContext, -In, +Err, LoopOut <: WCState[Ctx], +Out <: WCState[Ctx]](
      loop: WIO[LoopOut, Err, LoopOut, Ctx],
      stopCondition: LoopOut => Option[Out],
      current: WIO[In, Err, LoopOut, Ctx],
      onRestart: Option[WIO[LoopOut, Err, LoopOut, Ctx]],
      meta: Loop.Meta,
      isReturning: Boolean, // true if current is coming from onReturn
      history: Vector[WIO.Executed[Ctx, Err, LoopOut, ?]],
  ) extends WIO[In, Err, Out, Ctx]

  object Loop {
    case class Meta(
        releaseBranchName: Option[String],
        restartBranchName: Option[String],
        conditionName: Option[String],
    )
  }

  case class Fork[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      branches: Vector[Branch[In, Err, Out, Ctx, ?]],
      name: Option[String],
      selected: Option[Int],
  ) extends WIO[In, Err, Out, Ctx] {
    require(selected.forall(branches.indices.contains))
  }

  case class Embedded[Ctx <: WorkflowContext, -In, +Err, InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[
    InnerCtx,
  ]] <: WCState[Ctx]](
      inner: WIO[In, Err, InnerOut, InnerCtx],
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, MappingOutput, In],
  ) extends WIO[In, Err, MappingOutput[InnerOut], Ctx]

  // do we need imperative variant?
  case class HandleInterruption[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      base: WIO[In, Err, Out, Ctx],
      interruption: WIO[WCState[Ctx], Err, Out, Ctx],
      status: HandleInterruption.InterruptionStatus,
      interruptionType: HandleInterruption.InterruptionType,
  ) extends WIO[In, Err, Out, Ctx]

  object HandleInterruption {
    enum InterruptionType {
      case Signal, Timer
    }

    enum InterruptionStatus {
      case Pending, TimerStarted, Interrupted
    }
  }

  case class Timer[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      duration: Timer.DurationSource[In],
      startedEventHandler: EventHandler[In, Unit, WCEvent[Ctx], Timer.Started],
      name: Option[String],
      releasedEventHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Timer.Released],
  ) extends WIO[In, Err, Out, Ctx] {
    def getReleaseTime(started: Timer.Started, in: In): Instant = {
      val awaitDuration = duration match {
        case DurationSource.Static(duration)     => duration
        case DurationSource.Dynamic(getDuration) => getDuration(in)
      }
      val releaseTime   = started.at.plus(awaitDuration)
      releaseTime
    }
  }

  case class AwaitingTime[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      resumeAt: Instant,
      releasedEventHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Timer.Released],
  ) extends WIO[In, Err, Out, Ctx]

  object Timer {

    case class Started(at: Instant)
    case class Released(at: Instant)

    sealed trait DurationSource[-In]
    object DurationSource {
      case class Static(duration: Duration)                extends DurationSource[Any]
      // we could support IO[Duration] but then either the logic has to be more complicated or the event has to capture release time
      case class Dynamic[-In](getDuration: In => Duration) extends DurationSource[In]
    }
  }

  case class Parallel[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], InterimState <: WCState[Ctx]](
      elements: Seq[Parallel.Element[Ctx, In, Err, WCState[Ctx], InterimState]],
      formResult: Seq[WCState[Ctx]] => Out,
      initialInterimState: In => InterimState,
  ) extends WIO[In, Err, Out, Ctx]

  object Parallel {
    case class Element[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], InterimState](
        wio: WIO[In, Err, Out, Ctx],
        incorporateState: (InterimState, WCState[Ctx]) => InterimState,
    )
  }

  // -----

  def build[Ctx <: WorkflowContext]: AllBuilders[Ctx] = new AllBuilders[Ctx] {}

  case class Branch[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext, BranchIn](
      condition: In => Option[BranchIn],
      wio: () => WIO[BranchIn, Err, Out, Ctx],
      name: Option[String],
  )

  object Branch {
    def selected[Err, Out <: WCState[Ctx], Ctx <: WorkflowContext, BranchIn](
        branchIn: BranchIn,
        wio: => WIO[BranchIn, Err, Out, Ctx],
        name: Option[String],
    ): Branch[Any, Err, Out, Ctx, BranchIn] =
      Branch(_ => Some(branchIn), () => wio, name)
  }

  case class Executed[Ctx <: WorkflowContext, +Err, +Out <: WCState[Ctx], In](original: WIO[In, ?, ?, Ctx], output: Either[Err, Out], input: In)
      extends WIO[Any, Err, Out, Ctx]

  case class Discarded[Ctx <: WorkflowContext, In](original: WIO[In, ?, ?, Ctx], input: In) extends WIO[Any, Nothing, Nothing, Ctx]

  case class Interruption[Ctx <: WorkflowContext, +Err, +Out <: WCState[Ctx]](
      handler: WIO[WCState[Ctx], Err, Out, Ctx],
      tpe: HandleInterruption.InterruptionType,
  ) {
    def andThen[FinalErr, FinalOut <: WCState[Ctx]](
        f: WIO[WCState[Ctx], Err, Out, Ctx] => WIO[WCState[Ctx], FinalErr, FinalOut, Ctx],
    ): WIO.Interruption[Ctx, FinalErr, FinalOut] = {
      WIO.Interruption(f(handler), tpe)
    }
  }

  // This could also allow for raising errors.
  case class Checkpoint[Ctx <: WorkflowContext, -In, +Err, Out <: WCState[Ctx], Evt](
      base: WIO[In, Err, Out, Ctx],
      genEvent: (In, Out) => IO[Evt],
      eventHandler: EventHandler[In, Out, WCEvent[Ctx], Evt],
  ) extends WIO[In, Err, Out, Ctx]

  // This could also allow for optionality (do X if event is present,
  // do Y otherwise), but the implementation might be a bit convoluted, hence left for later.
  // This could also allow for raising errors.
  case class Recovery[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Evt](
      eventHandler: EventHandler[In, Out, WCEvent[Ctx], Evt],
  ) extends WIO[In, Err, Out, Ctx]

}
