package workflow4s.wio

import cats.effect.IO
import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.internal.{EventHandler, QueryHandler, SignalHandler}

import scala.annotation.unused
import scala.language.implicitConversions

trait WorkflowContext { ctx: WorkflowContext =>
  type Event
  type State

  type WIO[-In, +Err, +Out <: State] = workflow4s.wio.WIO[In, Err, Out, ctx.type]
  object WIO extends WIOBuilderMethods[ctx.type] {
    type Branch[-In, +Err, +Out <: State] = workflow4s.wio.WIO.Branch[In, Err, Out, ctx.type]
  }
}

object WorkflowContext {
  type AUX[St, Evt]                               = WorkflowContext { type State = St; type Event = Evt }
  type WithDifferentState[Ctx <: WorkflowContext] = WorkflowContext { type Event = Ctx#Event }
}

sealed trait WIO[-In, +Err, +Out <: Ctx#State, Ctx <: WorkflowContext] extends WIOMethods[Ctx, In, Err, Out]

object WIO {

  case class HandleSignal[Ctx <: WorkflowContext, -In, +Out <: Ctx#State, +Err, Sig, Resp, Evt](
      sigDef: SignalDef[Sig, Resp],
      sigHandler: SignalHandler[Sig, Evt, In],
      evtHandler: EventHandler[In, (Either[Err, Out], Resp), Ctx#Event, Evt],
      errorCt: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx] {
    def expects[Req1, Resp1](@unused signalDef: SignalDef[Req1, Resp1]): Option[HandleSignal[Ctx, In, Out, Err, Req1, Resp1, Evt]] = {
      Some(this.asInstanceOf[HandleSignal[Ctx, In, Out, Err, Req1, Resp1, Evt]]) // TODO
    }
  }

  case class HandleQuery[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State, -Qr, -QrState, +Resp](
      queryHandler: QueryHandler[Qr, QrState, Resp],
      inner: WIO[In, Err, Out, Ctx],
  ) extends WIO[In, Err, Out, Ctx]

  // theoretically state is not needed, it could be State.extract.flatMap(RunIO)
  case class RunIO[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State, Evt](
      buildIO: In => IO[Evt],
      evtHandler: EventHandler[In, Either[Err, Out], Ctx#Event, Evt],
      errorCt: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class FlatMap[Ctx <: WorkflowContext, Err1 <: Err2, Err2, Out1 <: Ctx#State, +Out2 <: Ctx#State, -In](
      base: WIO[In, Err1, Out1, Ctx],
      getNext: Out1 => WIO[Out1, Err2, Out2, Ctx],
      errorCt: ErrorMeta[_],
  ) extends WIO[In, Err2, Out2, Ctx]

  case class Map[Ctx <: WorkflowContext, In, Err, Out1 <: Ctx#State, -In2, +Out2 <: Ctx#State](
      base: WIO[In, Err, Out1, Ctx],
      contramapInput: In2 => In,
      mapValue: (In2, Out1) => Out2,
  ) extends WIO[In2, Err, Out2, Ctx]

  case class Pure[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State](
      value: In => Either[Err, Out],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  // TODO this should ne called `Never` or `Halt` or similar, as the workflow cant proceed from that point.
  case class Noop[Ctx <: WorkflowContext]() extends WIO[Any, Nothing, Nothing, Ctx]

  case class HandleError[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State, ErrIn](
      base: WIO[In, ErrIn, Out, Ctx],
      handleError: ErrIn => WIO[In, Err, Out, Ctx],
      handledErrorMeta: ErrorMeta[_],
      newErrorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class HandleErrorWith[Ctx <: WorkflowContext, -In, Err, +Out <: Ctx#State, +ErrOut](
      base: WIO[In, Err, Out, Ctx],
      handleError: WIO[(In, Err), ErrOut, Out, Ctx],
      handledErrorMeta: ErrorMeta[_],
      newErrorCt: ErrorMeta[_],
  ) extends WIO[In, ErrOut, Out, Ctx]

  case class Named[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State](
      base: WIO[In, Err, Out, Ctx],
      name: String,
      description: Option[String],
      errorMeta: ErrorMeta[_],
  ) extends WIO[In, Err, Out, Ctx]

  case class AndThen[Ctx <: WorkflowContext, -In, +Err, Out1 <: Ctx#State, +Out2 <: Ctx#State](
      first: WIO[In, Err, Out1, Ctx],
      second: WIO[Out1, Err, Out2, Ctx],
  ) extends WIO[In, Err, Out2, Ctx]

  // TODO name for condition
  case class DoWhile[Ctx <: WorkflowContext, -In, +Err, LoopOut <: Ctx#State, +Out <: Ctx#State](
      loop: WIO[LoopOut, Err, LoopOut, Ctx],
      stopCondition: LoopOut => Option[Out],
      current: WIO[In, Err, LoopOut, Ctx],
  ) extends WIO[In, Err, Out, Ctx]

  case class Fork[Ctx <: WorkflowContext, -In, +Err, +Out <: Ctx#State](branches: Vector[Branch[In, Err, Out, Ctx]]) extends WIO[In, Err, Out, Ctx]

  case class Embedded[Ctx <: WorkflowContext, -In, +Err, InnerCtx <: WorkflowContext, InnerOut <: InnerCtx#State, MappingOutput[?] <: Ctx#State](
      inner: WIO[In, Err, InnerOut, InnerCtx],
      embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, MappingOutput, In],
  ) extends WIO[In, Err, MappingOutput[InnerOut], Ctx]

  // -----

  def build[Ctx <: WorkflowContext]: WIOBuilderMethods[Ctx] = new WIOBuilderMethods[Ctx] {}

  trait Branch[-In, +Err, +Out <: Ctx#State, Ctx <: WorkflowContext] {
    type I // Intermediate

    def condition: In => Option[I]

    def wio: WIO[(In, I), Err, Out, Ctx]
  }

  object Branch {
    def apply[Ctx <: WorkflowContext, In, T, Err, Out <: Ctx#State](
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
