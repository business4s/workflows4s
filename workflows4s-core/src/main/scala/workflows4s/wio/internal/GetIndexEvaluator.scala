package workflows4s.wio.internal

import workflows4s.wio.*

private[workflows4s] object GetIndexEvaluator {

  def findMaxIndex(wio: WIO[?, ?, ?, ?]): Option[Int] = {
    val visitor = new GetIndexVisitor(wio)
    visitor.run
  }

  private class GetIndexVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[
    Ctx
  ]](
      wio: WIO[In, Err, Out, Ctx]
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[Int]

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = Some(
      wio.index
    )

    def onSignal[Sig, Evt, Resp](
        wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]
    ): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result = None
    def onNoop(wio: WIO.End[Ctx]): Result = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = None
    def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result =
      None
    def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result = recurse(
      wio.original
    )

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](
        wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]
    ): Result = recurse(wio.base)
    def onTransform[In1, Out1 <: WCState[Ctx], Err1](
        wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]
    ): Result = recurse(wio.base)
    def onCheckpoint[Evt, Out1 <: Out](
        wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]
    ): Result = recurse(wio.base)
    def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Option[Int] = recurse(
      wio.base
    )
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[
      InnerCtx
    ], MappingOutput[
        _ <: WCState[InnerCtx]
    ] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]
    ): Result = GetIndexEvaluator.findMaxIndex(wio.inner)

    def onAndThen[Out1 <: WCState[Ctx]](
        wio: WIO.AndThen[Ctx, In, Err, Out1, Out]
    ): Result =
      recurse(wio.second).orElse(recurse(wio.first))

    def onHandleErrorWith[ErrIn](
        wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]
    ): Result =
      recurse(wio.handleError).orElse(recurse(wio.base))

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]
    ): Result =
      // find index in current wio first, if empty then check history
      recurse(wio.current.wio).orElse(wio.history.lastOption.map(_.index))

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      wio.selected.flatMap(idx => recurse(wio.branches(idx).wio))

    def onHandleInterruption(
        wio: WIO.HandleInterruption[Ctx, In, Err, Out]
    ): Result =
      (recurse(wio.base) ++ recurse(wio.interruption)).maxOption

    def onParallel[InterimState <: WCState[Ctx]](
        wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]
    ): Result =
      wio.elements.flatMap(elem => recurse(elem.wio)).maxOption

    override def onForEach[
        ElemId,
        InnerCtx <: WorkflowContext,
        ElemOut <: WCState[InnerCtx],
        InterimState <: WCState[Ctx]
    ](
        wio: WIO.ForEach[
          Ctx,
          In,
          Err,
          Out,
          ElemId,
          InnerCtx,
          ElemOut,
          InterimState
        ]
    ): Option[Int] = {
      wio.stateOpt.flatMap(
        _.values.flatMap(x => GetIndexVisitor(x).run).maxOption
      )
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, E1, O1, Ctx]
    ): Option[Int] =
      new GetIndexVisitor(wio).run

  }
}
