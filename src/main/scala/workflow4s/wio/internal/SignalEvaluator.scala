package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{EventResponse, SignalResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, JournalPersistance, JournalWrite, SignalDef, WIO, WfAndState}

object SignalEvaluator {

  def handleSignal[Req, Resp, StIn, StOut, Err](
      signalDef: SignalDef[Req, Resp],
      req: Req,
      wio: WIO[Err, Any, StIn, StOut],
      state: Either[Err, StIn],
      interp: Interpreter,
  ): SignalResponse[Resp] = {
    val visitor = new SignalVisitor(wio, interp, signalDef, req)
    visitor
      .run(state)
      .leftMap(_.map(dOutIO => dOutIO.map { case (out, resp) => ActiveWorkflow(WIO.Noop(), interp, out) -> resp }))
      .map(_.map(wfIO => wfIO.map({ case (wf, resp) => ActiveWorkflow(wf.wio, interp, wf.value) -> resp })))
      .merge
      .map(SignalResponse.Ok(_))
      .getOrElse(SignalResponse.UnexpectedSignal())
  }

  private class SignalVisitor[Resp, Err, Out, StIn, StOut, Req](
      wio: WIO[Err, Out, StIn, StOut],
      interp: Interpreter,
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ) extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut  = Option[IO[(Either[Err, (StOut, Out)], Resp)]]
    type FlatMapOut = Option[IO[(WfAndState.T[Err, Out, StOut], Resp)]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp], state: StIn): DirectOut = {
      wio.sigHandler
        .run(signalDef)(req, state)
        .map(ioOpt =>
          for {
            evt   <- ioOpt
            _     <- interp.journal.save(evt)(wio.evtHandler.jw)
            result = wio.evtHandler.handle(state, evt)
          } yield result.map({ case (s, o) => (s, o) }) -> signalDef.respCt.unapply(wio.getResp(state, evt)).get, // TODO .get is unsafe
        )
    }

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err], state: StIn): DirectOut = None

    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut], state: StIn): FlatMapOut = {
      val visitor                          = new SignalVisitor(wio.base, interp, signalDef, req)
      val newWfOpt: visitor.DispatchResult = visitor.run(state.asRight)
      newWfOpt match {
        case Left(dOutOpt)   =>
          dOutOpt.map(dOutIO =>
            dOutIO.map({
              case (Left(err), resp)             => ??? //WfAndState(WIO.Noop(), err.asLeft) -> resp
              case (Right((state, value)), resp) => ??? //WfAndState(wio.getNext(value), (state, value).asRight) -> resp
            }),
          )
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO =>
            fmOutIO.map({ case (wf, resp) =>
              val newWIO: WIO[Err, Out, wf.StIn, StOut] =
                WIO.FlatMap(
                  wf.wio,
                  (x: wf.NextValue) =>
                    wf.value
                      .map({ case (_, value) => wio.getNext(x) })
                      .leftMap(err => WIO.Pure(Left(err)))
                      .merge,
                )
              WfAndState(newWIO, wf.value) -> resp
            }),
          )
      }

    }

    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], state: StIn): DispatchResult = {
      val visitor = new SignalVisitor(wio.base, interp, signalDef, req)
      visitor.run(state.asRight) match {
        case Left(dOutOpt)   =>
          dOutOpt
            .map(dOutIO =>
              dOutIO.map({ case (value, resp) =>
                value.map({ case (stOut, out) => (stOut, wio.mapValue(out)) }) -> resp
              }),
            )
            .asLeft
        case Right(fmOutOpt) =>
          fmOutOpt
            .map(fmOutIO =>
              fmOutIO.map({ case (wf, resp) =>
                val newWIO: WIO[Err, Out, wf.StIn, StOut] =
                  WIO.Map(
                    wf.wio,
                    (x: wf.NextValue) => wio.mapValue(x),
                  )
                WfAndState(newWIO, wf.value) -> resp
              }),
            )
            .asRight
      }
    }

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult = {
      val visitor = new SignalVisitor(wio.inner, interp, signalDef, req)
      visitor.run(state.asRight) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wfIO =>
              wfIO.map({ case (wf, resp) =>
                val preserved: WIO[wf.Err, wf.NextValue, wf.StIn, wf.StOut] = WIO.HandleQuery(wio.queryHandler, wf.wio)
                WfAndState(preserved, wf.value) -> resp
              }),
            )
            .asRight
      }
    }

    def onNoop(wio: WIO.Noop): DirectOut = None

  }
}
