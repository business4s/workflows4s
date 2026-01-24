package workflows4s.wio.internal

import workflows4s.wio.*

private[workflows4s] object GetSignalDefsEvaluator {

  def run(wio: WIO[?, ?, ?, ?], includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    val visitor = new GetSignalDefsVisitor(wio, includeRedeliverable)
    visitor.run
  }

  private class GetSignalDefsVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      includeRedeliverable: Boolean,
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = List[SignalDef[?, ?]]

    // Base cases
    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = List(wio.sigDef)
    override def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result                             =
      if (includeRedeliverable) recurse(wio.original) else Nil
    override def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = Nil
    override def onNoop(wio: WIO.End[Ctx]): Result                                                          = Nil
    override def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                           = Nil
    override def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = Nil
    override def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = Nil
    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result                         = Nil

    // Recursive cases
    override def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result                                                         = Nil
    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(
      wio.base, // the next step in FlatMap is already handled in onAndThen
    )
    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result   = recurse(wio.base)
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = Nil

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result = recurse(wio.base)
    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result                                                                                         = GetSignalDefsEvaluator.run(wio.inner, includeRedeliverable)
    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result   =
      if (includeRedeliverable) {
        recurse(wio.first) ++ recurse(wio.second)
      } else {
        wio.first.asExecuted match {
          case Some(_) => recurse(wio.second)
          case None    => recurse(wio.first)
        }
      }

    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result =
      if (includeRedeliverable) {
        recurse(wio.base) ++ recurse(wio.handleError)
      } else {
        wio.base.asExecuted match {
          case Some(baseExecuted) =>
            baseExecuted.output match {
              case Left(_)  => recurse(wio.handleError)
              case Right(_) => Nil
            }
          case None               => recurse(wio.base)
        }
      }

    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result                                                                                                         =
      if (includeRedeliverable) {
        wio.history.flatMap(executed => recurse(executed)).toList ++ recurse(wio.current.wio)
      } else {
        recurse(wio.current.wio)
      }
    override def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                     = wio.selected.map(idx => recurse(wio.branches(idx).wio)).getOrElse(Nil)
    override def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result                         = recurse(wio.base) ++ recurse(wio.interruption)
    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): Result =
      wio.elements.flatMap(elem => recurse(elem.wio)).toList

    def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result = recurse(wio.base)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): List[SignalDef[?, ?]] = {
      wio.stateOpt
        .getOrElse(Map())
        .flatMap(x => GetSignalDefsVisitor(x._2, includeRedeliverable).run)
        .toList
        .distinct
        .map(wio.signalRouter.outerSignalDef)
    }

    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx]): List[SignalDef[?, ?]] =
      new GetSignalDefsVisitor(wio, includeRedeliverable).run

  }
}
