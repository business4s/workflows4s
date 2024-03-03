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
    val visitor = new QueryVisitor(wio, signalDef, req)
    visitor
      .run(state)
      .merge
      .map(QueryResponse.Ok(_))
      .getOrElse(QueryResponse.UnexpectedQuery())
  }

  private class QueryVisitor[Err, Out, StIn, StOut, Resp, Req](wio: WIO[Err, Out, StIn, StOut], signalDef: SignalDef[Req, Resp], req: Req)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut  = Option[Resp]
    type FlatMapOut = Option[Resp]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp], state: StIn): DirectOut                      = None
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err], state: StIn): DirectOut                                        = None
    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut], state: StIn): FlatMapOut = {
      val visitor = new QueryVisitor(wio, signalDef, req)
      visitor.run(state.asRight).merge
    }
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], state: StIn): DispatchResult = {
      val visitor = new QueryVisitor(wio, signalDef, req)
      visitor.run(state.asRight).merge.asLeft
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult =
      wio.queryHandler.run(signalDef)(req, state).asLeft
    def onNoop(wio: WIO.Noop): DirectOut                                                                                        = None

  }

}
