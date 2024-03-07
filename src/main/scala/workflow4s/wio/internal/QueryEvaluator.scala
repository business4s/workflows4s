package workflow4s.wio.internal

import cats.syntax.all._
import workflow4s.wio.Interpreter.{EventResponse, QueryResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, SignalDef, WIO, WfAndState}

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
      recurse(wio.base)
    }
    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult    =
      recurse(wio.first)
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult = {
      recurse(wio.base)
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult =
      wio.queryHandler.run(signalDef)(req, state)
    def onNoop(wio: WIO.Noop): DispatchResult                                                                      = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                                    = recurse(wio.base)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult                                      = None

    override def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = recurse(wio.base)

    def recurse[E1, O1, SOut1](wio: WIO[E1, O1, StIn, SOut1]): QueryVisitor[E1, O1, StIn, SOut1, Resp, Req]#DispatchResult =
      new QueryVisitor(wio, signalDef, req, state).run

  }

}
