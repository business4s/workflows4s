package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{EventResponse, SignalResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, JournalPersistance, JournalWrite, SignalDef, WIO, WfAndState}

object SignalEvaluator {

  def handleSignal[Req, Resp, StIn, StOut](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO.States[StIn, StOut],
      state: StIn,
      interp: Interpreter,
  ): SignalResponse[Resp] = {
    val visitor = new SignalVisitor[Resp] {
      override def onSignal[Sig, StIn, StOut, Evt, O](wio: HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): Option[IO[(StOut, O, Resp)]] = {
        wio.sigHandler
          .run(signalDef)(req, state)
          .map(ioOpt =>
            for {
              evt            <- ioOpt
              _              <- interp.journal.save(evt)(wio.evtHandler.jw)
              (newState, out) = wio.evtHandler.handle(state, evt)
            } yield (newState, out, signalDef.respCt.unapply(out).get), // TODO .get is unsafe
          )
      }
    }
    visitor
      .dispatch(wio, state)
      .leftMap(_.map(dOutIO => dOutIO.map { case (state, value, resp) => ActiveWorkflow(state, WIO.Noop(), interp, value) -> resp }))
      .map(_.map(wfIO => wfIO.map({ case (wf, resp) => ActiveWorkflow(wf.state, wf.wio, interp, wf.value) -> resp })))
      .merge
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  abstract class SignalVisitor[Resp] extends Visitor {
    type DirectOut[StOut, O]        = Option[IO[(StOut, O, Resp)]]
    type FlatMapOut[Err, Out, SOut] = Option[IO[(WfAndState.T[Err, Out, SOut], Resp)]]

    override def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): DirectOut[StOut, O] = None
    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        state: StIn,
    ): DispatchResult[Err, Out, StOut]                                                                               =
      dispatch(wio.inner, state) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wfIO =>
              wfIO.map({ case (wf, resp) =>
                val preserved: WIO[wf.Err, wf.NextValue, wf.StIn, wf.StOut] = WIO.HandleQuery(wio.queryHandler, wf.wio)
                WfAndState(wf.state, preserved, wf.value) -> resp
              }),
            )
            .asRight
      }
    override def onNoop[St, O](wio: WIO.Noop): DirectOut[St, O]                                                      = None

    override def onFlatMap[Err, Out1, Out2, S0, S1, S2](wio: WIO.FlatMap[Err, Out1, Out2, S0, S1, S2], state: S0): FlatMapOut[Err, Out1, S2] = {
      val newWfOpt: DispatchResult[Err, Out1, S1] = dispatch(wio.base, state)
      newWfOpt match {
        case Left(dOutOpt)   => dOutOpt.map(dOutIO => dOutIO.map({ case (st, value, resp) => WfAndState(st, wio.getNext(value), value) -> resp }))
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO =>
            fmOutIO.map({ case (wf, resp) =>
              val newWIO: WIO[Err, Out2, wf.StIn, S2] = WIO.FlatMap(wf.wio, (_: wf.NextValue) => wio.getNext(wf.value))
              WfAndState(wf.state, newWIO, wf.value) -> resp
            }),
          )
      }
    }

    override def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): Either[DirectOut[StOut, Out2], FlatMapOut[Err, Out2, StOut]] = {
      dispatch(wio.base, state) match {
        case Left(dOutOpt)   => dOutOpt.map(dOutIO => dOutIO.map({ case (stOut, out, resp) => (stOut, wio.mapValue(out), resp) })).asLeft
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO => fmOutIO.map({ case (wf, resp) => WfAndState(wf.state, wf.wio, wio.mapValue(wf.value)) -> resp })).asRight
      }
    }

  }

}
