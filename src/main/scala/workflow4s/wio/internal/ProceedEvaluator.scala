package workflow4s.wio.internal

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{ProceedResponse, SignalResponse, Visitor}
import workflow4s.wio.WfAndState.T
import workflow4s.wio._

object ProceedEvaluator {

  def proceed[StIn, StOut, Err](wio: WIO.States[StIn, StOut], errOrState: Either[Err, StIn], interp: Interpreter): ProceedResponse = {
    val visitor = new ProceedVisitor(wio, interp, errOrState.toOption.get)
    visitor.run
      .leftMap(_.map(dOutIO => dOutIO.map { errOrValue => ActiveWorkflow(WIO.Noop(), interp, errOrValue) }))
      .map(_.map(wfIO => wfIO.map({ wf => ActiveWorkflow(wf.wio, interp, wf.value) })))
      .merge
      .map(ProceedResponse.Executed(_))
      .getOrElse(ProceedResponse.Noop())
  }

  private class ProceedVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], interp: Interpreter, state: StIn)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut  = Option[IO[Either[Err, (StOut, Out)]]]
    type FlatMapOut = Option[IO[WfAndState.T[Err, Out, StOut]]]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DirectOut = None
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DirectOut = {
      (for {
        evt <- wio.buildIO(state)
        _   <- interp.journal.save(evt)(wio.evtHandler.jw)
      } yield wio.evtHandler.handle(state, evt)).some
    }
    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut = {
      recurse(wio.base) match {
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
                      .leftMap(err => WIO.raise[StOut1](err))
                      .merge,
                )
              WfAndState(newWIO, wf.value)
            }),
          )
      }
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): Option[IO[T[Err, Out, StOut]]] = {
      recurse(wio.first) match {
        case Left(dOutOpt)   =>
          dOutOpt.map(dOutIO =>
            dOutIO.map({
              case Left(err)             => WfAndState(WIO.Noop(), err.asLeft)
              case Right((state, value)) => WfAndState(wio.second, (state, value).asRight)
            }),
          )
        case Right(fmOutOpt) =>
          fmOutOpt.map(fmOutIO =>
            fmOutIO.map({ wf =>
              val newWIO: WIO[Err, Out, wf.StIn, StOut] = WIO.AndThen(wf.wio, wio.second)
              WfAndState(newWIO, wf.value)
            }),
          )
      }
    }

    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult = {
      recurse(wio.base) match {
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
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner) match {
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
    def onNoop(wio: WIO.Noop): DirectOut                                                                  = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                           = recurse(wio.base)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DirectOut            = Some(wio.value(state).pure[IO])

    private def recurse[E1, O1, SOut1](wio: WIO[E1, O1, StIn, SOut1]): ProceedVisitor[E1, O1, StIn, SOut1]#DispatchResult =
      new ProceedVisitor(wio, interp, state).run
  }

}
