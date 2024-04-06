package workflow4s.wio.internal

import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.{Interpreter, NextWfState, Visitor, WIO, WorkflowContext}

object EventEvaluator {

  def handleEvent[Ctx <: WorkflowContext, StIn](
      event: Ctx#Event,
      wio: WIO[StIn, Nothing, Ctx#State, Ctx],
      state: StIn,
      interpreter: Interpreter[Ctx],
  ): EventResponse[Ctx] = {
    val visitor = new EventVisitor(wio, event, state)
    visitor.run
      .map(wf => wf.toActiveWorkflow(interpreter))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Ctx <: WorkflowContext, In, Err, Out <: Ctx#State](wio: WIO[In, Err, Out, Ctx], event: Ctx#Event, state: In)
      extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = NextWfState[Ctx, Err, Out]
    override type Result = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], Ctx#Event, Evt]): Result =
      handler
        .detect(event)
        .map(x => NextWfState.NewValue(handler.handle(state, x)))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result           = doHandle(
      wio.evtHandler.map(_._1),
    )
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                         = doHandle(wio.evtHandler)
    def onFlatMap[Out1 <: Ctx#State, Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result   =
      recurse(wio.base, state, event).map(preserveFlatMap(wio, _))
    def onMap[In1, Out1 <: Ctx#State](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                    =
      recurse(wio.base, wio.contramapInput(state), event).map(preserveMap(wio, _, state))
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[Ctx, In, Err, Out, Qr, QrState, Resp]): Result =
      recurse(wio.inner, state, event).map(preserveHandleQuery(wio, _))
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                   = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                   = recurse(wio.base, state, event)
    def onHandleError[ErrIn](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn]): Result                         =
      recurse(wio.base, state, event).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleError(wio, casted, state)
      })
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                 =
      recurse(wio.base, state, event).map((newWf: NextWfState[Ctx, ErrIn, Out]) => {
        val casted: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      })
    def onAndThen[Out1 <: Ctx#State](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                      =
      recurse(wio.first, state, event).map(preserveAndThen(wio, _))
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                     = None
    def onDoWhile[Out1 <: Ctx#State](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result                      =
      recurse(wio.current, state, event).map(applyOnDoWhile(wio, _))
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                     = ??? // TODO, proper error handling, should never happen
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: InnerCtx#State, MappingOutput[_] <: Ctx#State](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => new EventVisitor(wio.inner, convertedEvent, state).run)
        .map(convertResult(wio.embedding, _, state))
    }

    def recurse[C <: WorkflowContext, I1, E1, O1 <: C#State](wio: WIO[I1, E1, O1, C], s: I1, e: C#Event): EventVisitor[C, I1, E1, O1]#Result =
      new EventVisitor(wio, e, s).run

  }
}
