package workflows4s.wio.internal

import workflows4s.wio.*

private[workflows4s] object GetIndexEvaluator {

  def findMaxIndex[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ): Option[Int] = {
    runVisitor(wio)
  }

  /** Helper to capture existential types and satisfy Scala 3's strict constructor requirements.
    */
  private def runVisitor[F[_], C <: WorkflowContext, I, E, O <: WCState[C]](
      wio: WIO[F, I, E, O, C],
  ): Option[Int] = {
    new GetIndexVisitor[F, C, I, E, O](wio).run
  }

  private class GetIndexVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ) extends Visitor[F, Ctx, In, Err, Out](wio) {
    override type Result = Option[Int]

    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result = Some(wio.index)

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[F, Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                                           = None
    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result                           = None
    def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result                         = None
    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result                                     = recurse(wio.original)

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base)
    def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result   = recurse(wio.base)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base)
    def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result                           = recurse(wio.base)
    def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Option[Int]                                                        = recurse(wio.base)

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[
        _ <: WCState[InnerCtx],
    ] <: WCState[Ctx]](wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]): Result =
      runVisitor(wio.inner)

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result =
      recurse(wio.second).orElse(recurse(wio.first))

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result =
      recurse(wio.handleError).orElse(recurse(wio.base))

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result =
      recurse(wio.current.wio).orElse(wio.history.lastOption.map(_.index))

    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result =
      wio.selected.flatMap(idx => recurse(wio.branches(idx).wio))

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result =
      List(recurse(wio.base), recurse(wio.interruption)).flatten.maxOption

    def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState]): Result =
      wio.elements.flatMap(elem => recurse(elem.wio)).maxOption

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Option[Int] = {
      wio.stateOpt.flatMap(_.values.flatMap(childWio => runVisitor(childWio)).maxOption)
    }

    /** Helper for recursion within the same Context. */
    private def recurse[I1, E1, O1 <: WCState[Ctx]](target: WIO[F, I1, E1, O1, Ctx]): Option[Int] =
      runVisitor(target)
  }
}
