package workflow4s.wio

import cats.effect.IO
import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.internal.{EventHandler, QueryHandler, SignalHandler, WIOUtils}

import scala.annotation.unused
import scala.language.implicitConversions

trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State

  type WIO[-In, +Err, +Out <: State] = workflow4s.wio.WIO[In, Err, Out, ctx.type]
  object WIO extends WIOBuilderMethods[ctx.type] with HandleSignalBuilder.Step0[ctx.type] {
    type Branch[-In, +Err, +Out <: State]  = workflow4s.wio.WIO.Branch[In, Err, Out, ctx.type]
    type Interruption[+Err, +Out <: State] = workflow4s.wio.WIO.Interruption[ctx.type, Err, Out, ?, ?]

    def interruption = InterruptionBuilder.Step0[ctx.type]()
  }
}

object WorkflowContext {
  type AuxS[_S]                    = WorkflowContext { type State = _S }
  type AuxE[_E]                    = WorkflowContext { type Event = _E }
  type State[T <: WorkflowContext] = T match {
    case AuxS[s] => s
  }
  type Event[T <: WorkflowContext] = T match {
    case AuxE[s] => s
  }

  type AUX[St, Evt]                               = WorkflowContext { type State = St; type Event = Evt }
  type WithDifferentState[Ctx <: WorkflowContext] = WorkflowContext { type Event = WCEvent[Ctx] }
}

sealed trait WIO[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] extends WIOMethods[Ctx, In, Err, Out]

object WIO {

  sealed trait InterruptionSource { self: WIO[?, ?, ?, ?] => }

  case class HandleSignal[Ctx <: WorkflowContext, -In, +Out <: WCState[Ctx], +Err, Sig, Resp, Evt](
      sigDef: SignalDef[Sig, Resp],
      sigHandler: SignalHandler[Sig, Evt, In],
      evtHandler: EventHandler[In, (Either[Err, Out], Resp), WCEvent[Ctx], Evt],
      meta: HandleSignal.Meta
  ) extends WIO[In, Err, Out, Ctx]
      with InterruptionSource {
    def expects[Req1, Resp1](@unused signalDef: SignalDef[Req1, Resp1]): Option[HandleSignal[Ctx, In, Out, Err, Req1, Resp1, Evt]] = {
      Some(this.asInstanceOf[HandleSignal[Ctx, In, Out, Err, Req1, Resp1, Evt]]) // TODO
    }
  }
  object HandleSignal {
    // TODO, should the signal name be on handler level or in SignalDef?
    case class Meta(error: ErrorMeta[_], signalName: String, operationName: Option[String])
  }

  // theoretically state is not needed, it could be State.extract.flatMap(RunIO)
  case class RunIO[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], Evt](
      buildIO: In => IO[Evt],
      evtHandler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class FlatMap[Ctx <: WorkflowContext, Err1 <: Err2, Err2, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx], -In](
      base: WIO[In, Err1, Out1, Ctx],
      getNext: Out1 => WIO[Out1, Err2, Out2, Ctx],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err2, Out2, Ctx]

  case class Map[Ctx <: WorkflowContext, In, Err, Out1 <: WCState[Ctx], -In2, +Out2 <: WCState[Ctx]](
      base: WIO[In, Err, Out1, Ctx],
      contramapInput: In2 => In,
      mapValue: (In2, Out1) => Out2,
  ) extends WIO[In2, Err, Out2, Ctx]

  case class Pure[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      value: In => Either[Err, Out],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  // TODO this should ne called `Never` or `Halt` or similar, as the workflow cant proceed from that point.
  case class Noop[Ctx <: WorkflowContext]() extends WIO[Any, Nothing, Nothing, Ctx]

  case class HandleError[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx], ErrIn, TempOut <: WCState[Ctx]](
      base: WIO[In, ErrIn, Out, Ctx],
      handleError: ErrIn => (TempOut, WIO[TempOut, Err, Out, Ctx]),
      handledErrorMeta: ErrorMeta[_],
      newErrorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class HandleErrorWith[Ctx <: WorkflowContext, -In, Err, +Out <: WCState[Ctx], +ErrOut](
      base: WIO[In, Err, Out, Ctx],
      handleError: WIO[(In, Err), ErrOut, Out, Ctx],
      recoverState: (In, Err) => WCState[Ctx],
      handledErrorMeta: ErrorMeta[_],
      newErrorCt: ErrorMeta[_],
  ) extends WIO[In, ErrOut, Out, Ctx]

  case class Named[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](
      base: WIO[In, Err, Out, Ctx],
      name: String,
      description: Option[String],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class AndThen[Ctx <: WorkflowContext, -In, +Err, Out1 <: WCState[Ctx], +Out2 <: WCState[Ctx]](
      first: WIO[In, Err, Out1, Ctx],
      second: WIO[Out1, Err, Out2, Ctx],
  ) extends WIO[In, Err, Out2, Ctx]

  // TODO name for condition
  case class DoWhile[Ctx <: WorkflowContext, -In, +Err, LoopOut <: WCState[Ctx], +Out <: WCState[Ctx]](
      loop: WIO[LoopOut, Err, LoopOut, Ctx],
      stopCondition: LoopOut => Option[Out],
      current: WIO[In, Err, LoopOut, Ctx],
  ) extends WIO[In, Err, Out, Ctx]

  case class Fork[Ctx <: WorkflowContext, -In, +Err, +Out <: WCState[Ctx]](branches: Vector[Branch[In, Err, Out, Ctx]]) extends WIO[In, Err, Out, Ctx]

  case class Embedded[Ctx <: WorkflowContext, -In, +Err, InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[
    InnerCtx,
  ]] <: WCState[Ctx]](
      inner: WIO[In, Err, InnerOut, InnerCtx],
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, MappingOutput, In],
      initialState: In => WCState[InnerCtx], // should we move this into embedding?
  ) extends WIO[In, Err, MappingOutput[InnerOut], Ctx]

  // do we need imperative variant?
  case class HandleInterruption[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      base: WIO[In, Err, Out, Ctx],
      interruption: Interruption[Ctx, Err, Out, ?, ?],
  ) extends WIO[In, Err, Out, Ctx]

  // -----

  def build[Ctx <: WorkflowContext]: WIOBuilderMethods[Ctx] = new WIOBuilderMethods[Ctx] {}

  trait Branch[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext] {
    type I // Intermediate

    def condition: In => Option[I]

    def wio: WIO[(In, I), Err, Out, Ctx]
  }

  /*
  Needs:
  1. handle both dynamic and declarative
  2. allow to render speficically
  3. handle tranformInput
  Options:
  1. signaldef + signaldef => wio
  2. handlesignal + handlesignal => wio
  3. handlesignal + sequencing
   */
  case class Interruption[Ctx <: WorkflowContext, +Err, +Out <: WCState[Ctx], InitOut <: WCState[Ctx], InitErr](
      trigger: WIO.HandleSignal[Ctx, WCState[Ctx], InitOut, InitErr, ?, ?, ?],
      buildFinal: WIO[WCState[Ctx], InitErr, InitOut, Ctx] => WIO[WCState[Ctx], Err, Out, Ctx],
  ) {
    val finalWIO = buildFinal(trigger)
    assert(WIOUtils.getFirstRaw(finalWIO) == trigger)
  }

  object Branch {
    def apply[Ctx <: WorkflowContext, In, T, Err, Out <: WCState[Ctx]](
        cond: In => Option[T],
        wio0: WIO[(In, T), Err, Out, Ctx],
    ): Branch[In, Err, Out, Ctx] = {
      new Branch[In, Err, Out, Ctx] {
        override type I = T
        override def condition: In => Option[I]       = cond
        override def wio: WIO[(In, I), Err, Out, Ctx] = wio0
      }
    }
  }

}
