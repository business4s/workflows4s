package workflow4s.wio.internal

import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.{Interpreter, VisitorModule, WorkflowContext}

class EventEvaluator[Ctx <: WorkflowContext](val c: Ctx, interpreter: Interpreter[Ctx]) extends VisitorModule[Ctx] {

  def handleEvent[StIn, StOut](event: Ctx#Event, wio: Ctx#WIO[StIn, Nothing, StOut], state: StIn): EventResponse[Ctx] = {
    val visitor = new EventVisitor(wio, event, state) // TODO .toOption.get is wrong
    visitor.run
      .map(wf => wf.toActiveWorkflow(interpreter))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[In, Err, Out](wio: WIO[In, Err, Out], event: Ctx#Event, state: In) extends Visitor[In, Err, Out](wio) {
    type NewWf           = NextWfState[Err, Out]
    override type Result = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], Ctx#Event, Evt]): Result =
      handler
        .detect(event)
        .map(x => NextWfState.NewValue(handler.handle(state, x)))

    def makeCompilerHappy1(e: c.Event): Ctx#Event         = e
    def makeCompilerHappy2(e: Ctx#Event): Option[c.Event] = Some(e.asInstanceOf[c.Event]) // TODO, hack

    def onSignal[Sig, Evt, Resp](wio: WIOC#HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result           = doHandle(
      wio.evtHandler.map(_._1).xmapEvt(makeCompilerHappy2, makeCompilerHappy1),
    )
    def onRunIO[Evt](wio: WIOC#RunIO[In, Err, Out, Evt]): Result                                         = doHandle(wio.evtHandler.xmapEvt(makeCompilerHappy2, makeCompilerHappy1))
    def onFlatMap[Out1, Err1 <: Err](wio: WIOC#FlatMap[Err1, Err, Out1, Out, In]): Result                = recurse(wio.base, state).map(preserveFlatMap(wio, _))
    def onMap[In1, Out1](wio: WIOC#Map[In1, Err, Out1, In, Out]): Result                                 =
      recurse(wio.base, wio.contramapInput(state)).map(preserveMap(wio, _, state))
    def onHandleQuery[Qr, QrState, Resp](wio: WIOC#HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result =
      recurse(wio.inner, state).map(preserveHandleQuery(wio, _))
    def onNoop(wio: WIOC#Noop): Result                                                                   = None
    def onNamed(wio: WIOC#Named[In, Err, Out]): Result                                                   = recurse(wio.base, state)
    def onHandleError[ErrIn](wio: WIOC#HandleError[In, Err, Out, ErrIn]): Result                         =
      recurse(wio.base, state).map((newWf: NextWfState[ErrIn, Out]) => {
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleError(wio, casted, state)
      })
    def onHandleErrorWith[ErrIn](wio: WIOC#HandleErrorWith[In, ErrIn, Out, Err]): Result                 =
      recurse(wio.base, state).map((newWf: NextWfState[ErrIn, Out]) => {
        val casted: NextWfState[ErrIn, Out] { type Error = ErrIn } =
          newWf.asInstanceOf[NextWfState[ErrIn, Out] { type Error = ErrIn }] // TODO casting
        applyHandleErrorWith(wio, casted, state)
      })
    def onAndThen[Out1](wio: WIOC#AndThen[In, Err, Out1, Out]): Result                                   = recurse(wio.first, state).map(preserveAndThen(wio, _))
    def onPure(wio: WIOC#Pure[In, Err, Out]): Result                                                     = None
    def onDoWhile[Out1](wio: WIOC#DoWhile[In, Err, Out1, Out]): Result                                   = recurse(wio.current, state).map(applyOnDoWhile(wio, _))
    def onFork(wio: WIOC#Fork[In, Err, Out]): Result                                                     = ??? // TODO, proper error handling, should never happen

    def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], s: I1): EventVisitor[I1, E1, O1]#Result =
      new EventVisitor(wio, event, s).run

  }
}
