package workflow4s.wio.internal

import workflow4s.wio.Interpreter.{EventResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, WIO, WfAndState}
import cats.syntax.all._

object EventEvaluator {

  def handleEvent[StIn, StOut](event: Any, wio: WIO.States[StIn, StOut], state: StIn, interp: Interpreter): EventResponse = {
    val visitor = new EventVisitor {
      override def doHandle[Evt, StIn, StOut, Out](handler: EventHandler[Evt, StIn, StOut, Out], state: StIn): Option[(StOut, Out)] =
        handler
          .expects(event)
          .map(handler.handle(state, _))
    }
    visitor
      .dispatch(wio, state)
      .leftMap(_.map({ case (state, value) => ActiveWorkflow(state, WIO.Noop(), interp, value) }))
      .map(_.map(wf => ActiveWorkflow(wf.state, wf.wio, interp, wf.value)))
      .merge
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  abstract class EventVisitor extends Visitor {
    type DirectOut[StOut, O]        = Option[(StOut, O)]
    type FlatMapOut[Err, Out, SOut] = Option[WfAndState.T[Err, Out, SOut]]

    def doHandle[Evt, StIn, StOut, Out](handler: EventHandler[Evt, StIn, StOut, Out], state: StIn): Option[(StOut, Out)]

    override def onSignal[Sig, StIn, StOut, Evt, O](wio: HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): Option[(StOut, O)] =
      doHandle(wio.evtHandler, state)
    override def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): Option[(StOut, O)]               =
      doHandle(wio.evtHandler, state)

    override def onFlatMap[Err, Out1, Out2, S0, S1, S2](
        wio: WIO.FlatMap[Err, Out1, Out2, S0, S1, S2],
        state: S0,
    ): Option[WfAndState.T[Err, Out1, S2]] = {
      val newWfOpt: Either[Option[(S1, Out1)], Option[WfAndState.T[Err, Out1, S1]]] = dispatch(wio.base, state)
      newWfOpt match {
        case Left(dOutOpt)   => dOutOpt.map({ case (st, value) => WfAndState(st, wio.getNext(value), value) })
        case Right(fmOutOpt) =>
          fmOutOpt.map(wf => {
            val newWIO: WIO[Err, Out2, wf.StIn, S2] = WIO.FlatMap(wf.wio, (_: wf.NextValue) => wio.getNext(wf.value))
            WfAndState(wf.state, newWIO, wf.value)
          })
      }
    }
    override def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): Either[DirectOut[StOut, Out2], FlatMapOut[Err, Out2, StOut]] = {
      dispatch(wio.base, state) match {
        case Left(dOutOpt)   => dOutOpt.map({ case (stOut, out) => (stOut, wio.mapValue(out)) }).asLeft
        case Right(fmOutOpt) => fmOutOpt.map(wf => WfAndState(wf.state, wf.wio, wio.mapValue(wf.value))).asRight
      }
    }
    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        state: StIn,
    ): DispatchResult[Err, Out, StOut]                                                                                            =
      dispatch(wio.inner, state) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wf => {
              WfAndState(wf.state, WIO.HandleQuery(wio.queryHandler, wf.wio), wf.value)
            })
            .asRight
      }
    override def onNoop[St, O](wio: WIO.Noop): Option[(St, O)]                                                                    = None

  }

}
