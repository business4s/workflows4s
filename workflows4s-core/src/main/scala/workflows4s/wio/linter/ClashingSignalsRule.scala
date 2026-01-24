package workflows4s.wio.linter
import cats.effect.IO

import workflows4s.wio.Linter.Rule
import workflows4s.wio.*
import workflows4s.wio.internal.GetSignalDefsEvaluator

object ClashingSignalsRule extends Rule {
  override def id: String = "clashing-signals"

  override def check(wio: WIO[IO, ?, ?, ?, ?]): List[LinterIssue] = {
    val visitor = new ClashingSignalsVisitor(wio, List("root"))
    visitor.run
  }

  private class ClashingSignalsVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[IO, In, Err, Out, Ctx],
      path: List[String],
  ) extends Visitor[IO, Ctx, In, Err, Out](wio) {
    override type Result = List[LinterIssue]

    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[IO, Ctx, In, Out, Err, Sig, Resp, Evt]): List[LinterIssue] = Nil
    override def onRunIO[Evt](wio: WIO.RunIO[IO, Ctx, In, Err, Out, Evt]): List[LinterIssue]                               = Nil
    override def onNoop(wio: WIO.End[IO, Ctx]): List[LinterIssue]                                                          = Nil
    override def onPure(wio: WIO.Pure[IO, Ctx, In, Err, Out]): List[LinterIssue]                                           = Nil
    override def onTimer(wio: WIO.Timer[IO, Ctx, In, Err, Out]): List[LinterIssue]                                         = Nil
    override def onAwaitingTime(wio: WIO.AwaitingTime[IO, Ctx, In, Err, Out]): List[LinterIssue]                           = Nil
    override def onExecuted[In1](wio: WIO.Executed[IO, Ctx, Err, Out, In1]): List[LinterIssue]                             = Nil
    override def onDiscarded[In1](wio: WIO.Discarded[IO, Ctx, In1]): List[LinterIssue]                                     = Nil
    override def onRecovery[Evt](wio: WIO.Recovery[IO, Ctx, In, Err, Out, Evt]): List[LinterIssue]                         = Nil

    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[IO, Ctx, Err1, Err, Out1, Out, In]): List[LinterIssue]          =
      recurse(wio.base, "flatMap")
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[IO, Ctx, In, Err, Out, ErrIn, TempOut]): List[LinterIssue] =
      recurse(wio.base, "base")
    override def onRetry(wio: WIO.Retry[IO, Ctx, In, Err, Out]): List[LinterIssue]                                                             = recurse(wio.base, "retry")
    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[IO, Ctx, In1, Err1, Out1, In, Out, Err]): List[LinterIssue]   =
      recurse(wio.base, "transform")
    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[IO, Ctx, In, ErrIn, Out, Err]): List[LinterIssue]                           =
      recurse(wio.base, "base") ++ recurse(wio.handleError, "handler")
    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[IO, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): List[LinterIssue]                                                                                                                       =
      recurse(wio.body, "loopBody") ++ recurse(wio.onRestart, "onRestart")
    override def onFork(wio: WIO.Fork[IO, Ctx, In, Err, Out]): List[LinterIssue]                                                               =
      wio.branches.zipWithIndex.flatMap { case (branch, idx) => recurse(branch.wio, s"branch[${branch.name.getOrElse(idx.toString)}]") }.toList
    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[IO, Ctx, In, Err, Out1, Out]): List[LinterIssue]                             =
      recurse(wio.first, "first") ++ recurse(wio.second, "second")
    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[IO, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): List[LinterIssue]                                                                                                                       =
      new ClashingSignalsVisitor(wio.inner, path :+ "embedded").run

    override def onHandleInterruption(wio: WIO.HandleInterruption[IO, Ctx, In, Err, Out]): List[LinterIssue] = {
      val baseSignals    = getExpectedSignals(wio.base)
      val triggerSignals = getExpectedSignals(wio.interruption)
      val clashes        = baseSignals.intersect(triggerSignals)
      val clashIssues    =
        clashes.map(sig => LinterIssue(s"Clashing signal: ${sig.name} (${sig.id}) expected in both base and interruption trigger", id, path))
      clashIssues.toList ++ recurse(wio.base, "base") ++ recurse(wio.interruption, "interruption")
    }

    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[IO, Ctx, In, Err, Out, InterimState]): List[LinterIssue] = {
      val signalsPerElement = wio.elements.map(e => getExpectedSignals(e.wio))
      val clashIssues       = for {
        i     <- signalsPerElement.indices
        j     <- (i + 1) until signalsPerElement.length
        clash <- signalsPerElement(i).intersect(signalsPerElement(j))
      } yield LinterIssue(s"Clashing signal: ${clash.name} (${clash.id}) expected in parallel branches ${i} and ${j}", id, path)
      clashIssues.toList ++ wio.elements.zipWithIndex.flatMap { case (e, idx) => recurse(e.wio, s"branch[$idx]") }.toList
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[IO, Ctx, In, Err, Out1, Evt]): List[LinterIssue] = recurse(wio.base, "checkpoint")

    override def onForEach[Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[IO, Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState],
    ): List[LinterIssue] = {
      new ClashingSignalsVisitor(wio.elemWorkflow, path :+ "forEach").run
    }

    private def recurse(nextWio: WIO[IO, ?, ?, ?, Ctx], name: String): List[LinterIssue] = {
      new ClashingSignalsVisitor(nextWio, path :+ name).run
    }

    private def getExpectedSignals(wio: WIO[IO, ?, ?, ?, Ctx]): Set[SignalDef[?, ?]] = {
      GetSignalDefsEvaluator.run(wio).toSet
    }
  }
}
