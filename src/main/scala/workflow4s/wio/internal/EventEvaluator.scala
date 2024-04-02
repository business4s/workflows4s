package workflow4s.wio.internal

import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.{Interpreter, VisitorModule, WorkflowContext}

trait EventEvaluatorModule extends VisitorModule {
  import c.WIO

  object EventEvaluator {

    def handleEvent[StIn, StOut, Err](event: Any, wio: WIO.States[StIn, StOut], state: Either[Err, StIn], interp: Interpreter): EventResponse = {
      val visitor = new EventVisitor(wio, event, state.toOption.get) // TODO .toOption.get is wrong
      visitor.run
        .map(wf => wf.toActiveWorkflow(interp))
        .map(EventResponse.Ok(_))
        .getOrElse(EventResponse.UnexpectedEvent())
    }

    private class EventVisitor[In, Err, Out](wio: WIO[In, Err, Out], event: Any, state: In) extends Visitor[In, Err, Out](wio) {
      type NewWf           = NextWfState[Err, Out]
      override type Result = Option[NewWf]

      def doHandle[Evt](handler: EventHandler[Evt, In, Out, Err]): Result =
        handler
          .expects(event)
          .map(x => NextWfState.NewValue(handler.handle(state, x)))

      def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result           = doHandle(wio.evtHandler)
      def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                                         = doHandle(wio.evtHandler)
      def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result                = recurse(wio.base, state).map(preserveFlatMap(wio, _))
      def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                                 =
        recurse(wio.base, wio.contramapInput(state)).map(preserveMap(wio, _, state))
      def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result =
        recurse(wio.inner, state).map(preserveHandleQuery(wio, _))
      def onNoop(wio: WIO.Noop): Result                                                                   = None
      def onNamed(wio: WIO.Named[In, Err, Out]): Result                                                   = recurse(wio.base, state)
      def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result                         =
        recurse(wio.base, state).map((newWf: NextWfState[ErrIn, Out]) => {
          val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
            newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
          applyHandleError(wio, casted, state)
        })
      def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result                 =
        recurse(wio.base, state).map((newWf: NextWfState[ErrIn, Out]) => {
          val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
            newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
          applyHandleErrorWith(wio, casted, state)
        })
      def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                                   = recurse(wio.first, state).map(preserveAndThen(wio, _))
      def onPure(wio: WIO.Pure[In, Err, Out]): Result                                                     = None
      def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result                                   = recurse(wio.current, state).map(applyOnDoWhile(wio, _))
      def onFork(wio: WIO.Fork[In, Err, Out]): Result                                                     = ??? // TODO, proper error handling, should never happen

      def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], s: I1): EventVisitor[I1, E1, O1]#Result =
        new EventVisitor(wio, event, s).run

    }
  }

}
