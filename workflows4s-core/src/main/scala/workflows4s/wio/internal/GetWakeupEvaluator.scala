package workflows4s.wio.internal

import java.time.Instant
import workflows4s.wio.*

object GetWakeupEvaluator {

  def extractNearestWakeup[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ): Option[Instant] = {
    runVisitor(wio)
  }

  // Helper to "fully define" types for the visitor instantiation
  private def runVisitor[F[_], C <: WorkflowContext, I, E, O <: WCState[C]](wio: WIO[F, I, E, O, C]): Option[Instant] =
    new GetWakeupVisitor[F, C, I, E, O](wio).run

  private class GetWakeupVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ) extends Visitor[F, Ctx, In, Err, Out](wio) {

    override type Result = Option[Instant]

    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result = Some(wio.resumeAt)

    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                                         = None
    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result                             = runVisitor(wio.original)
    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[F, Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                                           = None
    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result                                     = runVisitor(wio.original)

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result          = runVisitor(wio.base)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result          = runVisitor(wio.base)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = runVisitor(wio.base)
    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result                                                    = runVisitor(wio.base)

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result =
      runVisitor(wio.current.wio)

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result   = runVisitor(wio.second).orElse(runVisitor(wio.first))
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result =
      runVisitor(wio.handleError).orElse(runVisitor(wio.base))
    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result                                     = wio.selected.flatMap(idx => runVisitor(wio.branches(idx).wio))

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
      val fromInterruption = runVisitor(wio.interruption)
      val fromBase         = runVisitor(wio.base)
      (fromBase, fromInterruption) match {
        case (Some(a), Some(b)) => Some(if a.isBefore(b) then a else b) // Avoid minOption ambiguity
        case (a, b)             => a.orElse(b)
      }
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState],
    ): Result = {
      wio.elements.flatMap(elem => runVisitor(elem.wio)).minOption
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result = runVisitor(wio.base)
    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result                   = None

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      runVisitor(wio.inner)
    }

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Option[Instant] = {
      wio.stateOpt match {
        case Some(state) =>
          state.values.flatMap(target => runVisitor(target)).minOption
        case None        =>
          runVisitor(wio.elemWorkflow)
      }
    }
  }
}
