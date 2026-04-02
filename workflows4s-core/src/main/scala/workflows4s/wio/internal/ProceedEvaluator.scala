package workflows4s.wio.internal

import cats.syntax.all.*
import workflows4s.wio.*

// For the given workflow tries to move it to next step if possible without executing any side-effecting comptations.
// This is most common in presence of `Pure` or timers awaiting the threshold.
object ProceedEvaluator {

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[F[_], Ctx <: WorkflowContext](
      wio: WIO[F, Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): Response[F, Ctx] = {
    val visitor: ProceedVisitor[F, Ctx, Any, Nothing, WCState[Ctx]] = new ProceedVisitor(wio, state, state, 0)
    Response(visitor.run.map(_.wio))
  }

  case class Response[F[_], Ctx <: WorkflowContext](newFlow: Option[WIO.Initial[F, Ctx]])

  private class ProceedVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      index: Int,
  ) extends ProceedingVisitor[F, Ctx, In, Err, Out](wio, input, lastSeenState, index) {

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result                               = None
    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result                           = None
    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result                = None

    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result =
      WFExecution.complete(wio, wio.value(input), input, index).some

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new ProceedVisitor(wio.inner, input, newState, index).run
        .map(convertEmbeddingResult2(wio, _, input))
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Option[NewWf] = {
      handleCheckpointBase(wio)
    }
    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Option[NewWf]                                                                                            = {
      val state            = wio.state(input)
      val maxIndex: Int    = GetIndexEvaluator.findMaxIndex(wio).getOrElse(index)
      def completeEmpty    = WFExecution.complete(wio, Right(wio.buildOutput(input, Map())), input, maxIndex + 1)
      def updateChild      = {
        val updatedElem = state.toList.collectFirstSome((elemId, elemWio) => {
          new ProceedVisitor(elemWio, input, wio.initialElemState(), maxIndex).run.tupleLeft(elemId)
        })
        updatedElem.map(newWf => convertForEachResult(wio, newWf._2, input, newWf._1))
      }
      def updateEmptyState = Option.when(wio.stateOpt.isEmpty)(WFExecution.Partial(wio.copy(stateOpt = Some(state))))
      if state.isEmpty then Some(completeEmpty)
      else updateChild.orElse(updateEmptyState)
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[F, I1, E1, O1, Ctx],
        in: I1,
        state: WCState[Ctx],
        index: Int,
    ): Option[WFExecution[F, Ctx, I1, E1, O1]] = {
      val nextIndex = Math.max(index, this.index) // handle parallel case
      new ProceedVisitor(wio, in, state, nextIndex).run
    }

  }

}
