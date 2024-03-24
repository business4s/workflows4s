package workflow4s.wio.internal

import workflow4s.wio.Interpreter.{EventResponse, Visitor}
import workflow4s.wio.WIO.EventHandler
import workflow4s.wio.{Interpreter, NextWfState, WIO}

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
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult                                        =
      doHandle(wio.evtHandler)

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, state).map(preserveFlatMap(wio, _))
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.first, state).map(preserveAndThen(wio, _))
    }

    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult = {
      recurse(wio.base, wio.contramapState(state)).map(preserveMap(wio, _, state))
    }

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      recurse(wio.inner, state).map(preserveHandleQuery(wio, _))
    }
    def onNoop(wio: WIO.Noop): DispatchResult                                                                           = None
    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult                                         = recurse(wio.base, state)
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult                                           = None

    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      recurse(wio.base, state).map((newWf: NextWfState[ErrIn, Out, StOut]) => {
        val casted: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out, StOut] { type Error = ErrIn }] // TODO casting
        applyHandleError(wio, casted, state)
      })
    }

    override def onDoWhile[StOut1](wio: WIO.DoWhile[Err, Out, StIn, StOut1, StOut]): DispatchResult =
      recurse(wio.current, state).map(applyOnDoWhile(wio, _))

    override def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](
        wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out],
    ): DispatchResult = {
      recurse(wio.base, state).map((newWf: NextWfState[ErrIn, BaseOut, StOut]) => {
        val casted: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out, StOut] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      })
    }

    override def onFork(wio: WIO.Fork[Err, Out, StIn, StOut]): DispatchResult =  ??? // TODO, proper error handling, should never happen

    def recurse[E1, O1, StIn1, SOut1](wio: WIO[E1, O1, StIn1, SOut1], s: StIn1): EventVisitor[E1, O1, StIn1, SOut1]#DispatchResult =
      new EventVisitor(wio, event, s).run

  }

}
