package workflows4s.wio.internal

import workflows4s.wio.*

private[workflows4s] object GetSignalDefsEvaluator {

  /** Now takes all 5 type parameters to match the call site in ActiveWorkflow.
    */
  def run[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ): List[SignalDef[?, ?]] = {
    runVisitor(wio)
  }

  /** Helper to capture existential types (In, Err, Out) and "fully define" them for the class constructor.
    */
  private def runVisitor[F[_], C <: WorkflowContext, I, E, O <: WCState[C]](
      wio: WIO[F, I, E, O, C],
  ): List[SignalDef[?, ?]] = {
    new GetSignalDefsVisitor[F, C, I, E, O](wio).run
  }

  private class GetSignalDefsVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
  ) extends Visitor[F, Ctx, In, Err, Out](wio) {
    override type Result = List[SignalDef[?, ?]]

    // Base cases
    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result =
      List(wio.sigDef)

    override def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result     = Nil
    override def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result       = Nil
    override def onNoop(wio: WIO.End[F, Ctx]): Result                                  = Nil
    override def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                   = Nil
    override def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                 = Nil
    override def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result   = Nil
    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result = Nil

    // Recursive cases
    override def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result                                                = Nil
    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result =
      runVisitor(wio.base)

    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      runVisitor(wio.base)

    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = Nil

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result =
      runVisitor(wio.base)

    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result =
      runVisitor(wio.inner)

    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result =
      wio.first.asExecuted match {
        case Some(_) => runVisitor(wio.second)
        case None    => runVisitor(wio.first)
      }

    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result =
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(_)  => runVisitor(wio.handleError)
            case Right(_) => Nil
          }
        case None               => runVisitor(wio.base)
      }

    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Result = runVisitor(wio.current.wio)

    override def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result =
      wio.selected.map(idx => runVisitor(wio.branches(idx).wio)).getOrElse(Nil)

    override def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result =
      runVisitor(wio.base) ++ runVisitor(wio.interruption)

    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState]): Result =
      wio.elements.flatMap(elem => runVisitor(elem.wio)).toList

    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result = runVisitor(wio.base)

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val innerSignals = wio.stateOpt
        .getOrElse(Map.empty)
        .values
        .flatMap(childWio => runVisitor(childWio))
        .toList
        .distinct

      innerSignals.map(wio.signalRouter.outerSignalDef)
    }
  }
}
