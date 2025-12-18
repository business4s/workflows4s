package workflows4s.wio.internal

import cats.syntax.all.*
import workflows4s.wio.*
import workflows4s.wio.Interpreter.EventResponse
import workflows4s.runtime.instanceengine.Effect

object EventEvaluator {

  /** Entry point updated to support F[_] and the full type stack.
    */
  def handleEvent[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      event: WCEvent[Ctx],
      wio: WIO[F, In, Err, Out, Ctx],
      state: In,
  )(using E: Effect[F]): EventResponse[F, Ctx] = {
    // Infer the static state to pass to the visitor
    val lastSeen = GetStateEvaluator.extractLastState(wio, state, state.asInstanceOf[WCState[Ctx]]).getOrElse(state.asInstanceOf[WCState[Ctx]])

    runVisitor(wio, event, state, lastSeen, 0)
      .map(execution => EventResponse.Ok(execution.wio.asInstanceOf[WIO.Initial[F, Ctx]]))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  /** Helper to "capture" existential types (I, E, O) and avoid "Type argument must be fully defined" errors.
    */
  private def runVisitor[F[_], C <: WorkflowContext, I, E, O <: WCState[C]](
      wio: WIO[F, I, E, O, C],
      event: WCEvent[C],
      input: I,
      lastSeenState: WCState[C],
      index: Int,
  )(using Effect[F]): Option[WFExecution[F, C, I, E, O]] = {
    new EventVisitor[F, C, I, E, O](wio, event, input, lastSeenState, index).run
  }

  private class EventVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      event: WCEvent[Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      index: Int,
  )(using E: Effect[F])
      extends ProceedingVisitor[F, Ctx, In, Err, Out](wio, input, lastSeenState, index) {

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt]): Result =
      handler
        .detect(event)
        .map(x => WFExecution.complete(wio, handler.handle(input, x), input, index))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = doHandle(wio.evtHandler.map(_._1))
    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result                               = doHandle(wio.evtHandler)
    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result                           = doHandle(wio.releasedEventHandler)
    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result                = doHandle(wio.eventHandler.map(_.asRight))

    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result   = None
    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result = {
      wio.startedEventHandler
        .detect(event)
        .map(started => {
          val releaseTime = wio.getReleaseTime(started, input)
          WIO.AwaitingTime(releaseTime, wio.releasedEventHandler)
        })
        .map(x => WFExecution.Partial(x.asInstanceOf[WIO[F, In, Err, Out, Ctx]]))
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] = wio.embedding.unconvertStateUnsafe(lastSeenState)
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => runVisitor(wio.inner, convertedEvent, input, newState, index))
        .map(convertEmbeddingResult2(wio, _, input))
    }

    def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result = {
      doHandle(wio.eventHandler.map(_.asRight)).orElse(handleCheckpointBase(wio))
    }

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val state          = wio.state(input)
      val nextIndex: Int = GetIndexEvaluator.findMaxIndex(wio).map(_ + 1).getOrElse(index)
      wio.eventEmbedding
        .unconvertEvent(event)
        .flatMap((elemId, convertedEvent) => runVisitor(state(elemId), convertedEvent, input, wio.initialElemState(), nextIndex).tupleLeft(elemId))
        .map((elemId, newExec) => convertForEachResult(wio, newExec, input, elemId))
    }

    /** Helper to correctly recurse within the same context (Ctx).
      */
    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[F, I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx] = lastSeenState,
        index: Int = index,
    ): Option[WFExecution[F, Ctx, I1, E1, O1]] = {
      val nextIndex = Math.max(index, this.index)
      runVisitor(wio, event, in, state, nextIndex)
    }
  }
}
