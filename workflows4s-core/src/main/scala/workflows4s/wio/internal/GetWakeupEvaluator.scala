package workflows4s.wio.internal

import workflows4s.wio.*

import java.time.Instant
import scala.math.Ordering.Implicits.infixOrderingOps

object GetWakeupEvaluator {

  def extractNearestWakeup[Ctx <: WorkflowContext, StIn <: WCState[Ctx]](
      wio: WIO[?, ?, WCState[Ctx], Ctx]
  ): Option[Instant] = {
    val visitor = new GetWakeupVisitor(wio)
    visitor.run
  }

  private class GetWakeupVisitor[
      Ctx <: WorkflowContext,
      In,
      Err,
      Out <: WCState[Ctx]
  ](
      wio: WIO[In, Err, Out, Ctx]
  ) extends Visitor[Ctx, In, Err, Out](wio) {

    override type Result = Option[Instant]

    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = Some(
      wio.resumeAt
    )

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = None
    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = None
    def onSignal[Sig, Evt, Resp](
        wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]
    ): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result = None
    def onNoop(wio: WIO.End[Ctx]): Result = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](
        wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]
    ): Result = recurse(wio.base)
    def onTransform[In1, Out1 <: State, Err1](
        wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]
    ): Result = recurse(wio.base)
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Result = recurse(
      wio.base
    )

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]
    ): Result =
      recurse(wio.current.wio)

    def onAndThen[Out1 <: WCState[Ctx]](
        wio: WIO.AndThen[Ctx, In, Err, Out1, Out]
    ): Result = recurse(wio.second).orElse(recurse(wio.first))
    def onHandleErrorWith[ErrIn](
        wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]
    ): Result = recurse(wio.handleError).orElse(recurse(wio.base))
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      wio.selected.flatMap(idx => recurse(wio.branches(idx).wio))

    def onHandleInterruption(
        wio: WIO.HandleInterruption[Ctx, In, Err, Out]
    ): Result = {
      val fromInterruption = recurse(wio.interruption)
      val fromBase = recurse(wio.base)
      (fromBase, fromInterruption) match {
        case (Some(a), Some(b)) => Some(a.min(b))
        case (a, b)             => a.orElse(b)
      }
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: workflows4s.wio.WIO.Parallel[Ctx, In, Err, Out, InterimState]
    ): Result = {
      wio.elements.flatMap(elem => recurse(elem.wio)).minOption
    }

    override def onCheckpoint[Evt, Out1 <: Out](
        wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]
    ): Result = recurse(wio.base)
    override def onRecovery[Evt](
        wio: WIO.Recovery[Ctx, In, Err, Out, Evt]
    ): Result = None

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[
      InnerCtx
    ], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]
    ): Result = recurse(wio.inner)

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
    ): Option[Instant] = {
      wio.stateOpt.flatMap(state => {
        state.values.flatMap(recurse).minOption
      })
    }

    def recurse(wio: WIO[?, ?, ?, ?]): Result = new GetWakeupVisitor(wio).run

  }
}
