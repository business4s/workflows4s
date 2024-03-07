package workflow4s.wio.internal

import workflow4s.wio.Interpreter.{EventResponse, Visitor}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}
import workflow4s.wio.{ActiveWorkflow, Interpreter, NextWfState, WIO, WfAndState}
import cats.syntax.all._
import workflow4s.wio.NextWfState.{NewBehaviour, NewValue}
import workflow4s.wio.WfAndState.T

object EventEvaluator {

  def handleEvent[StIn, StOut, Err](event: Any, wio: WIO.States[StIn, StOut], state: Either[Err, StIn], interp: Interpreter): EventResponse = {
    val visitor = new EventVisitor(wio, event, state.toOption.get) // TODO .toOption.get is wrong
    visitor.run
      .map(wf => wf.toActiveWorkflow(interp))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], event: Any, state: StIn)
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type NewWf                   = NextWfState[Err, Out, StOut]
    override type DispatchResult = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[Evt, StIn, StOut, Out, Err]): DispatchResult =
      handler
        .expects(event)
        .map(x => NextWfState.NewValue(handler.handle(state, x)))

    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult =
      doHandle(wio.evtHandler)
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult                                        = doHandle(wio.evtHandler)

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base).map(preserveFlatMap(wio, _))
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.first).map(preserveAndThen(wio, _))
    }

    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult = {
      recurse(wio.base).map(preserveMap(wio, _))
    }

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner).map(preserveHandleQuery(wio, _))
    }
    def onNoop(wio: WIO.Noop): DispatchResult                                   = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult = recurse(wio.base)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult   = None

    override def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      recurse(wio.base).map(applyHandleError(wio, _))
    }

    def recurse[E1, O1, SOut1](wio: WIO[E1, O1, StIn, SOut1]): EventVisitor[E1, O1, StIn, SOut1]#DispatchResult =
      new EventVisitor(wio, event, state).run

  }

}
