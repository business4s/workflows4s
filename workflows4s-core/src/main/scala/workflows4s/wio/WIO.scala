package workflows4s.wio

import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.wio.WIO.Timer.DurationSource
import workflows4s.wio.builders.AllBuilders
import workflows4s.wio.internal.{EventHandler, GetStateEvaluator, SignalHandler, WorkflowEmbedding}
import workflows4s.wio.model.WIOMeta

import java.time.{Duration, Instant}
import scala.language.implicitConversions

sealed trait WIO[F[_], -In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] extends WIOMethods[F, Ctx, In, Err, Out]

object WIO {

  type Initial[F[_], Ctx <: WorkflowContext] = WIO[F, Any, Nothing, WCState[Ctx], Ctx]
  type Draft[F[_], Ctx <: WorkflowContext]   = WIO[F, Any, Nothing, Nothing, Ctx]

  type IHandleSignal[F[_], -In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] = HandleSignal[F, Ctx, In, Out, Err, ?, ?, ?]

  case class HandleSignal[F[_], Ctx <: WorkflowContext, -In, +Out <: WCState[Ctx], +Err, Sig, Resp, Evt](
      sigDef: SignalDef[Sig, Resp],
      sigHandler: SignalHandler[F, Sig, Evt, In],
      evtHandler: EventHandler[In, (Either[Err, Out], Resp), WCEvent[Ctx], Evt],
      meta: HandleSignal.Meta,
  ) extends WIO[F, In, Err, Out, Ctx] {

    def toInterruption(using ev: WCState[Ctx] <:< In): Interruption[F, Ctx, Err, Out] =
      WIO.Interruption(ev.substituteContra[[t] =>> WIO[F, t, Err, Out, Ctx]](this), WIO.HandleInterruption.InterruptionType.Signal)
  }

  object HandleSignal {
    case class Meta(error: ErrorMeta[?], signalName: String, operationName: Option[String])
  }

  case class RunIO[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Evt](
      buildIO: In => F[Evt],
      evtHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt],
      meta: RunIO.Meta,
  ) extends WIO[F, In, Err, Out, Ctx]

  object RunIO {
    case class Meta(error: ErrorMeta[?], name: Option[String], description: Option[String])
  }

  case class Pure[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      value: In => Either[Err, Out],
      meta: Pure.Meta,
  ) extends WIO[F, In, Err, Out, Ctx]

  object Pure {
    case class Meta(error: ErrorMeta[?], name: Option[String])
  }

  case class Transform[F[_], Ctx <: WorkflowContext, In1, Err1, Out1 <: WCState[Ctx], -In2, +Out2 <: WCState[Ctx], +Err2](
      base: WIO[F, In1, Err1, Out1, Ctx],
      contramapInput: In2 => In1,
      mapOutput: (In2, Either[Err1, Out1]) => Either[Err2, Out2],
  ) extends WIO[F, In2, Err2, Out2, Ctx]

  case class End[F[_], Ctx <: WorkflowContext]() extends WIO[F, Any, Nothing, Nothing, Ctx]

  case class FlatMap[F[_], Ctx <: WorkflowContext, Err1 <: Err2, +Err2, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx], -In](
      base: WIO[F, In, Err1, Out1, Ctx],
      getNext: Out1 => WIO[F, Out1, Err2, Out2, Ctx],
      errorMeta: ErrorMeta[?],
  ) extends WIO[F, In, Err2, Out2, Ctx]

  case class HandleError[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], ErrIn, TempOut <: WCState[Ctx]](
      base: WIO[F, In, ErrIn, Out, Ctx],
      handleError: (WCState[Ctx], ErrIn) => WIO[F, Any, Err, Out, Ctx],
      handledErrorMeta: ErrorMeta[?],
      newErrorMeta: ErrorMeta[?],
  ) extends WIO[F, In, Err, Out, Ctx]

  case class HandleErrorWith[F[_], Ctx <: WorkflowContext, -In, Err, +Out <: WCState[Ctx], +ErrOut](
      base: WIO[F, In, Err, Out, Ctx],
      handleError: WIO[F, (WCState[Ctx], Err), ErrOut, Out, Ctx],
      handledErrorMeta: ErrorMeta[?],
      newErrorMeta: ErrorMeta[?],
  ) extends WIO[F, In, ErrOut, Out, Ctx]

  case class AndThen[F[_], Ctx <: WorkflowContext, -In, +Err, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx]](
      first: WIO[F, In, Err, Out1, Ctx],
      second: WIO[F, Out1, Err, Out2, Ctx],
  ) extends WIO[F, In, Err, Out2, Ctx]

  case class Loop[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
      body: WIO[F, BodyIn, Err, BodyOut, Ctx],
      stopCondition: BodyOut => Either[ReturnIn, Out],
      onRestart: WIO[F, ReturnIn, Err, BodyIn, Ctx],
      current: Loop.State[F, Ctx, In, Err, BodyIn, BodyOut],
      meta: Loop.Meta,
      history: Vector[WIO.Executed[F, Ctx, Err, WCState[Ctx], ?]],
  ) extends WIO[F, In, Err, Out, Ctx]

  object Loop {
    sealed trait State[F[_], Ctx <: WorkflowContext, -In, +Err, BodyIn, BodyOut] {
      def wio: WIO[F, In, Err, WCState[Ctx], Ctx]
    }

    object State {
      case class Forward[F[_], Ctx <: WorkflowContext, In, Err, BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx]](wio: WIO[F, In, Err, BodyOut, Ctx])
          extends State[F, Ctx, In, Err, BodyIn, BodyOut]

      case class Backward[F[_], Ctx <: WorkflowContext, In, Err, BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx]](wio: WIO[F, In, Err, BodyIn, Ctx])
          extends State[F, Ctx, In, Err, BodyIn, BodyOut]

      case class Finished[F[_], Ctx <: WorkflowContext, In, Err, BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx]](
          wio: WIO.Executed[F, Ctx, Err, BodyOut, In],
      ) extends State[F, Ctx, In, Err, BodyIn, BodyOut]
    }

    case class Meta(
        releaseBranchName: Option[String],
        restartBranchName: Option[String],
        conditionName: Option[String],
    )
  }

  case class Fork[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      branches: Vector[Branch[F, In, Err, Out, Ctx, ?]],
      name: Option[String],
      selected: Option[Int],
  ) extends WIO[F, In, Err, Out, Ctx]

  case class Embedded[F[_], Ctx <: WorkflowContext, -In, +Err, InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[
    InnerCtx,
  ]] <: WCState[Ctx]](
      inner: WIO[F, In, Err, InnerOut, InnerCtx],
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, MappingOutput, In],
  ) extends WIO[F, In, Err, MappingOutput[InnerOut], Ctx]

  case class HandleInterruption[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      base: WIO[F, In, Err, Out, Ctx],
      interruption: WIO[F, WCState[Ctx], Err, Out, Ctx],
      status: HandleInterruption.InterruptionStatus,
      interruptionType: HandleInterruption.InterruptionType,
  ) extends WIO[F, In, Err, Out, Ctx]

  object HandleInterruption {
    enum InterruptionType {
      case Signal, Timer
    }

    enum InterruptionStatus {
      case Pending, TimerStarted, Interrupted
    }
  }

  case class Timer[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      duration: Timer.DurationSource[In],
      startedEventHandler: EventHandler[In, Unit, WCEvent[Ctx], Timer.Started],
      name: Option[String],
      releasedEventHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Timer.Released],
  ) extends WIO[F, In, Err, Out, Ctx] {
    def getReleaseTime(started: Timer.Started, in: In): Instant = {
      val awaitDuration = duration match {
        case DurationSource.Static(duration)     => duration
        case DurationSource.Dynamic(getDuration) => getDuration(in)
      }
      started.at.plus(awaitDuration)
    }

    def toInterruption(using ev: WCState[Ctx] <:< In): Interruption[F, Ctx, Err, Out] =
      WIO.Interruption(ev.substituteContra[[t] =>> WIO[F, t, Err, Out, Ctx]](this), WIO.HandleInterruption.InterruptionType.Timer)
  }

  case class AwaitingTime[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      resumeAt: Instant,
      releasedEventHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Timer.Released],
  ) extends WIO[F, In, Err, Out, Ctx]

  object Timer {
    case class Started(at: Instant)

    case class Released(at: Instant)

    sealed trait DurationSource[-In]

    object DurationSource {
      case class Static(duration: Duration) extends DurationSource[Any]

      case class Dynamic[-In](getDuration: In => Duration) extends DurationSource[In]
    }
  }

  case class Parallel[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], InterimState <: WCState[Ctx]](
      elements: Seq[Parallel.Element[F, Ctx, In, Err, WCState[Ctx], InterimState]],
      formResult: Seq[WCState[Ctx]] => Out,
      initialInterimState: In => InterimState,
  ) extends WIO[F, In, Err, Out, Ctx]

  object Parallel {
    case class Element[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], InterimState](
        wio: WIO[F, In, Err, Out, Ctx],
        incorporateState: (InterimState, WCState[Ctx]) => InterimState,
    )
  }

  case class Retry[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      base: WIO[F, In, Err, Out, Ctx],
      onError: (Throwable, WCState[Ctx], Instant) => F[Option[Instant]],
  ) extends WIO[F, In, Err, Out, Ctx]

  case class Executed[F[_], Ctx <: WorkflowContext, +Err, +Out <: WCState[Ctx], In](
      original: WIO[F, In, ?, ?, Ctx],
      output: Either[Err, Out],
      input: In,
      index: Int,
  ) extends WIO[F, Any, Err, Out, Ctx] {

    def lastState(prevState: WCState[Ctx]): Option[WCState[Ctx]] = output match {
      case Left(_)      =>
        GetStateEvaluator.extractLastState[F, Ctx, In, Any, WCState[Ctx]](
          original,
          input,
          prevState,
        )
      case Right(value) => Some(value)
    }
  }

  case class Discarded[F[_], Ctx <: WorkflowContext, In](original: WIO[F, In, ?, ?, Ctx], input: In) extends WIO[F, Any, Nothing, Nothing, Ctx]

  case class Interruption[F[_], Ctx <: WorkflowContext, +Err, +Out <: WCState[Ctx]](
      handler: WIO[F, WCState[Ctx], Err, Out, Ctx],
      tpe: HandleInterruption.InterruptionType,
  ) {
    def andThen[Err2 >: Err, Out2 <: WCState[Ctx]](
        f: WIO[F, WCState[Ctx], Err2, Out, Ctx] => WIO[F, WCState[Ctx], Err2, Out2, Ctx],
    ): Interruption[F, Ctx, Err2, Out2] =
      Interruption(f(handler), tpe)
  }

  case class Checkpoint[F[_], Ctx <: WorkflowContext, -In, +Err, Out <: WCState[Ctx], Evt](
      base: WIO[F, In, Err, Out, Ctx],
      genEvent: (In, Out) => F[Evt],
      eventHandler: EventHandler[In, Out, WCEvent[Ctx], Evt],
  ) extends WIO[F, In, Err, Out, Ctx]

  case class Recovery[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Evt](
      eventHandler: EventHandler[In, Out, WCEvent[Ctx], Evt],
  ) extends WIO[F, In, Err, Out, Ctx]

  case class ForEach[F[_], Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[
    InnerCtx,
  ], InterimState <: WCState[Ctx]](
      getElements: In => Set[Elem],
      elemWorkflow: WIO[F, Elem, Err, ElemOut, InnerCtx],
      initialElemState: () => WCState[InnerCtx],
      eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]],
      interimStateBuilder: (In, Map[Elem, WCState[InnerCtx]]) => InterimState,
      buildOutput: (In, Map[Elem, ElemOut]) => Out,
      stateOpt: Option[Map[Elem, WIO[F, Any, Err, ElemOut, InnerCtx]]],
      signalRouter: SignalRouter.Receiver[Elem, InterimState],
      meta: WIOMeta.ForEach,
  ) extends WIO[F, In, Err, Out, Ctx] {
    def state(input: In): Map[Elem, WIO[F, Any, Err, ElemOut, InnerCtx]] =
      stateOpt.getOrElse(getElements(input).map(elemId => elemId -> elemWorkflow.provideInput(elemId)).toMap)

    def interimState(input: In): InterimState = {
      val initial    = initialElemState()
      val elemStates = state(input).view.mapValues(w => GetStateEvaluator.extractLastState(w, (), initial).getOrElse(initial)).toMap
      interimStateBuilder(input, elemStates)
    }
  }

  def build[F[_], Ctx <: WorkflowContext]: AllBuilders[F, Ctx] = new AllBuilders[F, Ctx] {}

  case class Branch[F[_], -In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext, BranchIn](
      condition: In => Option[BranchIn],
      wio: WIO[F, BranchIn, Err, Out, Ctx],
      name: Option[String],
  )

  object Branch {
    def selected[F[_], Err, Out <: WCState[Ctx], Ctx <: WorkflowContext, BranchIn](
        branchIn: BranchIn,
        wio: WIO[F, BranchIn, Err, Out, Ctx],
        name: Option[String] = None,
    ): Branch[F, BranchIn, Err, Out, Ctx, BranchIn] =
      Branch(_ => Some(branchIn), wio, name)
  }
}
