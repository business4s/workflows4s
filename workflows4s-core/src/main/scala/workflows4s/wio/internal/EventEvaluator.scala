package workflows4s.wio.internal

import workflows4s.wio.*
import workflows4s.wio.Interpreter.EventResponse

object EventEvaluator {

  def handleEvent[Ctx <: WorkflowContext](
      event: WCEvent[Ctx],
      wio: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): EventResponse[Ctx] = {
    val visitor: EventVisitor[Ctx, Any, Nothing, WCState[Ctx]] = new EventVisitor(wio, event, state, state)
    visitor.run
      .map(wf => wf.toActiveWorkflow(state))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      event: WCEvent[Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends ProceedingVisitor[Ctx, In, Err, Out](wio, input, lastSeenState) {

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt]): Result =
      handler
        .detect(event)
        .map(x => WFExecution.complete(wio, handler.handle(input, x), input))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = doHandle(wio.evtHandler.map(_._1))
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = doHandle(wio.evtHandler)
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = doHandle(wio.releasedEventHandler)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                           = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = {
      wio.startedEventHandler
        .detect(event)
        .map(started => {
          val releaseTime = wio.getReleaseTime(started, input)
          WIO.AwaitingTime(releaseTime, wio.releasedEventHandler)
        })
        .map(WFExecution.Partial(_))
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => new EventVisitor(wio.inner, convertedEvent, input, newState).run)
        .map(convertEmbeddingResult2(wio, _, input))
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1): EventVisitor[Ctx, I1, E1, O1]#Result =
      new EventVisitor(wio, event, s, lastSeenState).run

  }
}
