package workflow4s.wio.internal

import workflow4s.wio.Interpreter.QueryResponse
import workflow4s.wio.{Interpreter, SignalDef, VisitorModule, WorkflowContext}

class QueryEvaluator[Ctx <: WorkflowContext](val c: Ctx, interpreter: Interpreter[Ctx]) extends VisitorModule[Ctx] {
  import c.WIO

  def handleQuery[Req, Resp, State, Err](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: Ctx#WIO[State, Err, Any],
      state: State,
  ): QueryResponse[Resp] = {
    val visitor = new QueryVisitor(wio, signalDef, req, state)
    visitor.run
      .map(QueryResponse.Ok(_))
      .getOrElse(QueryResponse.UnexpectedQuery())
  }

  private class QueryVisitor[Err, Out, In, Resp, Req](
      wio: WIO[In, Err, Out],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: In,
  ) extends Visitor[In, Err, Out](wio) {
    override type Result = Option[Resp]

    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result =
      wio.queryHandler.run(signalDef)(req, state)

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                               = None
    def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result      = recurse(wio.base, state)
    def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                       = recurse(wio.base, wio.contramapInput(state))
    def onNoop(wio: WIO.Noop): Result                                                         = None
    def onNamed(wio: WIO.Named[In, Err, Out]): Result                                         = recurse(wio.base, state)
    def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result               = recurse(wio.base, state)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result       = recurse(wio.base, state)
    def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                         = recurse(wio.first, state)
    def onPure(wio: WIO.Pure[In, Err, Out]): Result                                           = None
    def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result                         = recurse(wio.current, state)
    def onFork(wio: WIO.Fork[In, Err, Out]): Result                                           = ??? // TODO, proper error handling, should never happen

    def recurse[In1, E1, O1](wio: WIO[In1, E1, O1], s: In1): QueryVisitor[E1, O1, In1, Resp, Req]#Result =
      new QueryVisitor(wio, signalDef, req, s).run

  }
}
