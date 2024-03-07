package workflow4s.wio.internal

import workflow4s.wio.Interpreter.{EventResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, WIO, WfAndState}
import cats.syntax.all._
import workflow4s.wio.WfAndState.T

object EventEvaluator {

  def handleEvent[StIn, StOut, Err](event: Any, wio: WIO.States[StIn, StOut], state: Either[Err, StIn], interp: Interpreter): EventResponse = {
    val visitor = new EventVisitor(wio, event, state.toOption.get) // TODO .toOption.get is wrong
    visitor.run
      .leftMap(_.map(errOrOut => ActiveWorkflow(WIO.Noop(), interp, errOrOut)))
      .map(_.map(wf => ActiveWorkflow(wf.wio, interp, wf.value)))
      .merge
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], event: Any, state: StIn)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    override type DirectOut  = Option[Either[Err, (StOut, Out)]]
    type NewWf               = WfAndState.T[Err, Out, StOut]
    override type FlatMapOut = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[Evt, StIn, StOut, Out, Err]): Option[Either[Err, (StOut, Out)]] =
      handler
        .expects(event)
        .map(handler.handle(state, _))

    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DirectOut = doHandle(wio.evtHandler)
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DirectOut                                        = doHandle(wio.evtHandler)

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut = {
      recurse(wio.base) match {
        case Left(dOutOpt)   =>
          dOutOpt.map({
            case Left(err)             => WfAndState(WIO.Noop(), (err: Err).asLeft)
            case Right((state, value)) => WfAndState(wio.getNext(value), (state, value).asRight)
          })
        case Right(fmOutOpt) =>
          fmOutOpt.map(wf => {
            val newWIO: WIO[Err, Out, wf.StIn, StOut] =
              WIO.FlatMap[wf.NextError, Err, Out1, Out, wf.StIn, StOut1, StOut](
                wf.wio,
                (x: wf.NextValue) =>
                  wf.value
                    .map({ case (_, value) => wio.getNext(x) })
                    .leftMap(err => WIO.raise[StOut1](err))
                    .merge,
              )
            WfAndState(newWIO, wf.value)
          })
      }
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): Option[T[Err, Out, StOut]] = {
      recurse(wio.first) match {
        case Left(dOutOpt)   =>
          dOutOpt.map({
            case Left(err)             => WfAndState(WIO.Noop(), err.asLeft)
            case Right((state, value)) => WfAndState(wio.second, (state, value).asRight)
          })
        case Right(fmOutOpt) =>
          fmOutOpt.map(wf => {
            val newWIO: WIO[Err, Out, wf.StIn, StOut] = WIO.AndThen(wf.wio, wio.second)
            WfAndState(newWIO, wf.value)
          })
      }
    }

    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult = {
      recurse(wio.base) match {
        case Left(dOutOpt)   => dOutOpt.map(_.map({ case (stOut, out) => (stOut, wio.mapValue(out)) })).asLeft
        case Right(fmOutOpt) =>
          fmOutOpt
            .map(wf => {
              val newWIO: WIO[Err, Out, wf.StIn, StOut] =
                WIO.Map(
                  wf.wio,
                  (x: wf.NextValue) => wio.mapValue(x),
                )
              WfAndState(newWIO, wf.value)
            })
            .asRight
      }
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner) match {
        case Left(value)  => Left(value) // if its direct, we leave the query
        case Right(value) =>
          value
            .map(wf => {
              WfAndState(WIO.HandleQuery(wio.queryHandler, wf.wio), wf.value)
            })
            .asRight
      }
    }
    def onNoop(wio: WIO.Noop): DirectOut                                        = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult = recurse(wio.base)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DirectOut        = None

    override def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      def newWf(err: ErrIn): NewWf = WfAndState(wio.handleError(err), Left(err))
      val result: DispatchResult   = recurse(wio.base) match {
        case Left(directOutOpt)    =>
          directOutOpt match {
            case Some(Left(err))    => newWf(err).some.asRight
            case Some(Right(value)) => value.asRight[Err].some.asLeft
            case None               => None.asLeft
          }
        case Right(indirectOutOpt) =>
          (indirectOutOpt.map(newWfX => {
            newWfX.value match {
              case Left(err)    => newWf(err)
              case Right(value) => WfAndState.widenErr(newWfX)
            }
          }): Option[NewWf]).asRight
      }
      result
    }

    def recurse[E1, O1, SOut1](wio: WIO[E1, O1, StIn, SOut1]): EventVisitor[E1, O1, StIn, SOut1]#DispatchResult =
      new EventVisitor(wio, event, state).run

  }

}
