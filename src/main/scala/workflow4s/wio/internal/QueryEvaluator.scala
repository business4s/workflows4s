package workflow4s.wio.internal

import workflow4s.wio.Interpreter.QueryResponse
import workflow4s.wio.*

object QueryEvaluator {

  def handleQuery[Ctx <: WorkflowContext, Req, Resp, State, Err](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[State, Err, Ctx#State, Ctx],
      state: State
  ): QueryResponse[Resp] = {
    val visitor = new QueryVisitor(wio, signalDef, req, state)
    visitor.run
      .map(QueryResponse.Ok(_))
      .getOrElse(QueryResponse.UnexpectedQuery())
  }

  private class QueryVisitor[Ctx <: WorkflowContext, Err, Out <: Ctx#State, In, Resp, Req](
      wio: WIO[In, Err, Out, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: In,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[Resp]

    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[Ctx, In, Err, Out, Qr, QrState, Resp]): Result =
      wio.queryHandler.run(signalDef)(req, state)

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                                            = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                                          = None
    def onFlatMap[Out1 <: Ctx#State, Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result                                    = recurse(wio.base, state)
    def onMap[In1, Out1 <: Ctx#State](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                                                     = recurse(wio.base, wio.contramapInput(state))
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                                                    = None
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                                                    = recurse(wio.base, state)
    def onHandleError[ErrIn](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn]): Result                                                          = recurse(wio.base, state)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                                                  = recurse(wio.base, state)
    def onAndThen[Out1 <: Ctx#State](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                                                       = recurse(wio.first, state)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                                                      = None
    def onDoWhile[Out1 <: Ctx#State](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result                                                       = recurse(wio.current, state)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                                                      = ??? // TODO, proper error handling, should never happen
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: InnerCtx#State, MappingOutput[_] <: Ctx#State](wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]): Result = {
      recurse(wio.inner, state)
    }

    def recurse[C <: WorkflowContext, In1, E1, O1 <: C#State](wio: WIO[In1, E1, O1, C], s: In1): QueryVisitor[C, E1, O1, In1, Resp, Req]#Result =
      new QueryVisitor(wio, signalDef, req, s).run

  }
}
