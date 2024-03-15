package workflow4s.wio.internal

import workflow4s.wio.Interpreter.{QueryResponse, Visitor}
import workflow4s.wio.{SignalDef, WIO}

object QueryEvaluator {

  def handleQuery[Req, Resp, StIn, Err](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[Err, Any, StIn, Any],
      state: Either[Err, StIn],
  ): QueryResponse[Resp] = {
    val visitor = new QueryVisitor(wio, signalDef, req, state.toOption.get)
    visitor.run
      .map(QueryResponse.Ok(_))
      .getOrElse(QueryResponse.UnexpectedQuery())
  }

  private class QueryVisitor[Err, Out, StIn, StOut, Resp, Req](
      wio: WIO[Err, Out, StIn, StOut],
      signalDef: SignalDef[Req, Resp],
      req: Req,
      state: StIn,
  ) extends Visitor[Err, Out, StIn, StOut](wio) {
    override type DispatchResult = Option[Resp]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult     = None
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult                                   = None
    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, state)
    }
    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult    =
      recurse(wio.first, state)
    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, wio.contramapState(state))
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult =
      wio.queryHandler.run(signalDef)(req, state)
    def onNoop(wio: WIO.Noop): DispatchResult                                                                      = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                                    = recurse(wio.base, state)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult                                      = None

    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = recurse(wio.base, state)

    override def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](
        wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out],
    ): DispatchResult = recurse(wio.base, state)

    def recurse[E1, O1, StIn1, SOut1](wio: WIO[E1, O1, StIn1, SOut1], s: StIn1): QueryVisitor[E1, O1, StIn, SOut1, Resp, Req]#DispatchResult =
      new QueryVisitor(wio, signalDef, req, s).run

  }

}
