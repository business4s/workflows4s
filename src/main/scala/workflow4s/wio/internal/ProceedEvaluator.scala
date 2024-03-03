package workflow4s.wio.internal

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{ProceedResponse, SignalResponse, Visitor}
import workflow4s.wio._

object ProceedEvaluator {

  def proceed[StIn, StOut, Err](wio: WIO.States[StIn, StOut], errOrState: Either[Err, StIn], interp: Interpreter): ProceedResponse = {
    val visitor = new ProceedVisitor(wio, interp)
    visitor
      .run(errOrState)
      .leftMap(_.map(dOutIO => dOutIO.map { errOrValue => ActiveWorkflow(WIO.Noop(), interp, errOrValue) }))
      .map(_.map(wfIO => wfIO.map({ wf => ActiveWorkflow(wf.wio, interp, wf.value) })))
      .merge
      .map(ProceedResponse.Executed(_))
      .getOrElse(ProceedResponse.Noop())
  }

  private class ProceedVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], interp: Interpreter)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut  = Option[IO[Either[Err, (StOut, Out)]]]
    type FlatMapOut = Option[IO[WfAndState.T[Err, Out, StOut]]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp], state: StIn): DirectOut = None
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err], state: StIn): DirectOut = {
      (for {
        evt <- wio.buildIO(state)
        _   <- interp.journal.save(evt)(wio.evtHandler.jw)
      } yield wio.evtHandler.handle(state, evt)).some
    }
    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut], state: StIn): FlatMapOut = {
      val visitor                          = new ProceedVisitor(wio.base, interp)
      val newWfOpt: visitor.DispatchResult = visitor.run(state.asRight)
      newWfOpt match {
        case Left(dOutOpt)   =>
          dOutOpt.map(dOutIO =>
            dOutIO.map({
              case Left(err)             => WfAndState(WIO.Noop(), err.asLeft)
              case Right((state, value)) => WfAndState(wio.getNext(value), (state, value).asRight)
            }),
          )
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO =>
            fmOutIO.map({ wf =>
              val newWIO: WIO[Err, Out, wf.StIn, StOut] =
                WIO.FlatMap(
                  wf.wio,
                  (x: wf.NextValue) =>
                    wf.value
                      .map({ case (_, value) => wio.getNext(x) })
                      .leftMap(err => WIO.Pure(Left(err)))
                      .merge,
                )
              WfAndState(newWIO, wf.value)
            }),
          )
      }
    }
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], state: StIn): DispatchResult = {
      val visitor = new ProceedVisitor(wio.base, interp)
      visitor.run(state.asRight) match {
        case Left(dOutOpt)   => dOutOpt.map(dOutIO => dOutIO.map(_.map({ case (stOut, out) => (stOut, wio.mapValue(out)) }))).asLeft
        case Right(fmOutOpt) =>
          fmOutOpt
            .map(fmOutIO =>
              fmOutIO.map({ wf =>
                val newWIO: WIO[Err, Out, wf.StIn, StOut] =
                  WIO.Map(
                    wf.wio,
                    (x: wf.NextValue) => wio.mapValue(x),
                  )
                WfAndState(newWIO, wf.value)
              }),
            )
            .asRight
      }
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult = {
      val visitor = new ProceedVisitor(wio.inner, interp)
      visitor.run(state.asRight) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wfIO =>
              wfIO.map(wf => {
                val preserved: WIO[wf.Err, wf.NextValue, wf.StIn, wf.StOut] = WIO.HandleQuery(wio.queryHandler, wf.wio)
                WfAndState(preserved, wf.value)
              }),
            )
            .asRight
      }

    }
    def onNoop(wio: WIO.Noop): DirectOut                                                                   = None

  }

}
