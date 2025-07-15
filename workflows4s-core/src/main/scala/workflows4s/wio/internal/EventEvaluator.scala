package workflows4s.wio.internal

import cats.implicits.{catsSyntaxEitherId, toFunctorOps}
import workflows4s.wio.*
import workflows4s.wio.Interpreter.EventResponse

object EventEvaluator {

  def handleEvent[Ctx <: WorkflowContext](
      event: WCEvent[Ctx],
      wio: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): EventResponse[Ctx] = {
    val visitor: EventVisitor[Ctx, Any, Nothing, WCState[Ctx]] = new EventVisitor(wio, event, state, state, 0)
    visitor.run
      .map(execution => EventResponse.Ok(execution.wio))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      event: WCEvent[Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      index: Int,
  ) extends ProceedingVisitor[Ctx, In, Err, Out](wio, input, lastSeenState, index) {

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt]): Result =
      handler
        .detect(event)
        .map(x => WFExecution.complete(wio, handler.handle(input, x), input, index))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = doHandle(wio.evtHandler.map(_._1))
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = doHandle(wio.evtHandler)
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = doHandle(wio.releasedEventHandler)
    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result                = doHandle(wio.eventHandler.map(_.asRight))

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result   = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      wio.startedEventHandler
        .detect(event)
        .map(started => {
          val releaseTime = wio.getReleaseTime(started, input)
          WIO.AwaitingTime(releaseTime, wio.releasedEventHandler)
        })
        .map(WFExecution.Partial(_))
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] = wio.embedding.unconvertStateUnsafe(lastSeenState)
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => new EventVisitor(wio.inner, convertedEvent, input, newState, index).run)
        .map(convertEmbeddingResult2(wio, _, input))
    }

    def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result = {
      doHandle(wio.eventHandler.map(_.asRight)).orElse(handleCheckpointBase(wio))
    }

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val state = wio.state(input)
      val nexIndex: Int = GetIndexEvaluator.findMaxIndex(wio).map(_ + 1).getOrElse(index)
      wio.eventEmbedding
        .unconvertEvent(event)
        .flatMap((elemId, convertedEvent) => new EventVisitor(state(elemId), convertedEvent, input, wio.initialElemState(), nexIndex).run.tupleLeft(elemId))
        .map( (elemId, newExec) => convertForEachResult(wio, newExec, input, elemId))
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx],
        index: Int,
    ): EventVisitor[Ctx, I1, E1, O1]#Result = {
      val nextIndex = Math.max(index, this.index) // handle parallel case
      new EventVisitor(wio, event, in, state, nextIndex).run
    }

  }
}
