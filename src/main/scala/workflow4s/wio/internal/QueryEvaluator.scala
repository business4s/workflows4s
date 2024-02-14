package workflow4s.wio.internal

import cats.syntax.all._
import workflow4s.wio.Interpreter.{EventResponse, QueryResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, SignalDef, WIO, WfAndState}

object QueryEvaluator {

  def handleQuery[Req, Resp, StIn](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Any, Any, StIn, Any], state: StIn): QueryResponse[Resp] = {
    val visitor = new QueryVisitor[Resp] {
      override def doHandleQuery[Qr, St, Out](handler: WIO.QueryHandler[Qr, St, Out], state: Any): Option[Resp] = handler.run(signalDef)(req, state)
    }
    visitor
      .dispatch(wio, state)
      .merge
      .map(QueryResponse.Ok(_))
      .getOrElse(QueryResponse.UnexpectedQuery())
  }

  abstract class QueryVisitor[Resp] extends Visitor {
    type DirectOut[StOut, O]        = Option[Resp]
    type FlatMapOut[Err, Out, SOut] = Option[Resp]

    def doHandleQuery[Qr, St, Out](handler: WIO.QueryHandler[Qr, St, Out], s: Any): Option[Resp]

    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        state: StIn,
    ): DispatchResult[Err, Out, StOut] =
      doHandleQuery[Qr, QrSt, Resp](wio.queryHandler, state).asLeft

    override def onSignal[Sig, StIn, StOut, Evt, O](wio: HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): Option[Resp] = None
    override def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): Option[Resp]               = None
    override def onNoop[St, O](wio: WIO.Noop): Option[Resp]                                                                 = None

    override def onFlatMap[Err, Out1, Out2, S0, S1, S2](
        wio: WIO.FlatMap[Err, Out1, Out2, S0, S1, S2],
        state: S0,
    ): Option[Resp] = dispatch(wio.base, state).merge

    override def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): DispatchResult[Err, Out2, StOut] = dispatch[Err, Out1, StIn, StOut](wio.base, state)

  }

}
