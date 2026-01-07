package workflows4s.wio.linter

import workflows4s.wio.Linter.Rule
import workflows4s.wio.*

object UnnecessaryErrorHandlerRule extends Rule {
  override def id: String = "unnecessary-error-handler"

  override def check(wio: WIO[?, ?, ?, ?]): List[LinterIssue] = {
    val visitor = new UnnecessaryErrorHandlerVisitor(wio, List("root"))
    visitor.run
  }

  private class UnnecessaryErrorHandlerVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      path: List[String],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = List[LinterIssue]

    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): List[LinterIssue] = {
      val canFail    = canSubtreeFail(wio.base)
      val errorIssue = if !canFail then List(LinterIssue("Unnecessary error handler: base workflow cannot fail", id, path)) else Nil
      errorIssue ++ recurse(wio.base, "base")
    }

    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): List[LinterIssue] = {
      val canFail    = canSubtreeFail(wio.base)
      val errorIssue = if !canFail then List(LinterIssue("Unnecessary error handler: base workflow cannot fail", id, path)) else Nil
      errorIssue ++ recurse(wio.base, "base") ++ recurse(wio.handleError, "handler")
    }

    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): List[LinterIssue] = Nil
    override def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): List[LinterIssue]                               = Nil
    override def onNoop(wio: WIO.End[Ctx]): List[LinterIssue]                                                          = Nil
    override def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): List[LinterIssue]                                           = Nil
    override def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): List[LinterIssue]                                         = Nil
    override def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): List[LinterIssue]                           = Nil
    override def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): List[LinterIssue]                             = Nil
    override def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): List[LinterIssue]                                     = Nil
    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): List[LinterIssue]                         = Nil

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): List[LinterIssue]        =
      recurse(wio.base, "flatMap")
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): List[LinterIssue]                                                           = recurse(wio.base, "retry")
    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): List[LinterIssue] =
      recurse(wio.base, "transform")

    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): List[LinterIssue]                                                                                                         =
      recurse(wio.body, "loopBody") ++ recurse(wio.onRestart, "onRestart")
    override def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): List[LinterIssue]                                                     =
      wio.branches.zipWithIndex.flatMap { case (branch, idx) => recurse(branch.wio, s"branch[${branch.name.getOrElse(idx.toString)}]") }.toList
    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): List[LinterIssue]                   =
      recurse(wio.first, "first") ++ recurse(wio.second, "second")
    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): List[LinterIssue]                                                                                                         = new UnnecessaryErrorHandlerVisitor(wio.inner, path :+ "embedded").run
    override def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): List[LinterIssue]                         =
      recurse(wio.base, "base") ++ recurse(wio.interruption, "interruption")
    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): List[LinterIssue] =
      wio.elements.zipWithIndex.flatMap { case (e, idx) => recurse(e.wio, s"branch[$idx]") }.toList
    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): List[LinterIssue]                 = recurse(wio.base, "checkpoint")
    override def onForEach[Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState],
    ): List[LinterIssue]                                                                                                         =
      new UnnecessaryErrorHandlerVisitor(wio.elemWorkflow, path :+ "forEach").run

    private def recurse(nextWio: WIO[?, ?, ?, Ctx], name: String): List[LinterIssue] = new UnnecessaryErrorHandlerVisitor(nextWio, path :+ name).run

    private def canSubtreeFail(wio: WIO[?, ?, ?, Ctx]): Boolean = {
      val visitor = new CanFailVisitor(wio)
      visitor.run
    }
  }

  private class CanFailVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx])
      extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Boolean
    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Boolean                     = wio.meta.error.canFail
    override def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Boolean                                                   = wio.meta.error.canFail
    override def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Boolean                                                               = wio.meta.error.canFail
    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Boolean                           = wio.newErrorMeta.canFail
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Boolean =
      wio.newErrorMeta.canFail

    override def onNoop(wio: WIO.End[Ctx]): Boolean                                                                            = false
    override def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Boolean                                                           = false
    override def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Boolean                                             = false
    override def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Boolean                                               = false
    override def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Boolean                                                       = false
    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Boolean                                           = {
      // This will have to be changed when we enable errors from recover events
      false
    }
    override def onRetry(wio: WIO.Retry[Ctx, In, Err, Out]): Boolean                                                           = recurse(wio.base)
    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Boolean        = recurse(wio.base)
    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Boolean = recurse(wio.base)
    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): Boolean                                                                                                                 = recurse(wio.body) || recurse(wio.onRestart)
    override def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Boolean                                                             = wio.branches.exists(b => recurse(b.wio))
    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Boolean                           = recurse(wio.first) || recurse(wio.second)
    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Boolean                                                                                                                 = new CanFailVisitor(wio.inner).run
    override def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Boolean                                 = recurse(wio.base) || recurse(wio.interruption)
    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[Ctx, In, Err, Out, InterimState]): Boolean         =
      wio.elements.exists(e => recurse(e.wio))
    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Boolean                         = {
      // This will have to be changed when we enable errors from recover events
      recurse(wio.base)
    }
    override def onForEach[Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState],
    ): Boolean                                                                                                                 = new CanFailVisitor(wio.elemWorkflow).run

    private def recurse(nextWio: WIO[?, ?, ?, Ctx]): Boolean = new CanFailVisitor(nextWio).run
  }
}
