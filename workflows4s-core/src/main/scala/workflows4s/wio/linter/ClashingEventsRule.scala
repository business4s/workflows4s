package workflows4s.wio.linter
import cats.effect.IO

import workflows4s.wio.Linter.Rule
import workflows4s.wio.*
import workflows4s.wio.internal.{EventHandler, TypedEventHandler}

import scala.reflect.ClassTag

object ClashingEventsRule extends Rule {
  override def id: String = "clashing-events"

  override def check(wio: WIO[IO, ?, ?, ?, ?]): List[LinterIssue] = {
    val visitor = new ClashingEventsVisitor(wio, List("root"))
    visitor.run
  }

  private class ClashingEventsVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
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
    ): List[LinterIssue]                                                                                                                       = {
      new ClashingEventsVisitor(wio.inner, path :+ "embedded").run
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[IO, Ctx, In, Err, Out1, Evt]): List[LinterIssue] = {
      val baseEvents       = getExpectedEvents(wio.base)
      val checkpointEvents = extractMatchedClass(wio.eventHandler).toSet
      val clashes          = baseEvents.intersect(checkpointEvents)
      val clashIssues      = clashes.map(evt =>
        LinterIssue(
          s"Clashing event: ${evt.runtimeClass.getSimpleName} expected in both base and checkpoint. This will lead to non-deterministic recovery.",
          id,
          path,
        ),
      )
      clashIssues.toList ++ recurse(wio.base, "checkpoint")
    }

    private def extractMatchedClass(eh: EventHandler[?, ?, ?, ?]): List[ClassTag[?]] = eh match {
      case te: TypedEventHandler[?, ?, ?, ?] => List(te.matchedClass)
      case _                                 => Nil
    }

    override def onHandleInterruption(wio: WIO.HandleInterruption[IO, Ctx, In, Err, Out]): List[LinterIssue] = {
      val baseEvents    = getExpectedEvents(wio.base)
      val triggerEvents = getExpectedEvents(wio.interruption)
      val clashes       = baseEvents.intersect(triggerEvents)
      val clashIssues   = clashes.map(evt =>
        LinterIssue(
          s"Clashing event: ${evt.runtimeClass.getSimpleName} expected in both base and interruption trigger. This will lead to non-deterministic recovery.",
          id,
          path,
        ),
      )
      clashIssues.toList ++ recurse(wio.base, "base") ++ recurse(wio.interruption, "interruption")
    }

    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[IO, Ctx, In, Err, Out, InterimState]): List[LinterIssue] = {
      val eventsPerElement = wio.elements.map(e => getExpectedEvents(e.wio))
      val clashIssues      = for {
        i     <- eventsPerElement.indices
        j     <- (i + 1) until eventsPerElement.length
        clash <- eventsPerElement(i).intersect(eventsPerElement(j))
      } yield LinterIssue(
        s"Clashing event: ${clash.runtimeClass.getSimpleName} expected in parallel branches ${i} and ${j}. This will lead to non-deterministic recovery.",
        id,
        path,
      )
      clashIssues.toList ++ wio.elements.zipWithIndex.flatMap { case (e, idx) => recurse(e.wio, s"branch[$idx]") }.toList
    }

    override def onForEach[Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[IO, Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState],
    ): List[LinterIssue] = new ClashingEventsVisitor(wio.elemWorkflow, path :+ "forEach").run

    private def recurse(nextWio: WIO[IO, ?, ?, ?, Ctx], name: String): List[LinterIssue] = new ClashingEventsVisitor(nextWio, path :+ name).run

    private def getExpectedEvents(wio: WIO[IO, ?, ?, ?, Ctx]): Set[ClassTag[?]] = {
      val visitor = new ExpectedEventsVisitor(wio)
      visitor.run.toSet
    }
  }

  private class ExpectedEventsVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[IO, In, Err, Out, Ctx],
  ) extends Visitor[IO, Ctx, In, Err, Out](wio) {
    override type Result = List[ClassTag[?]]

    override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[IO, Ctx, In, Out, Err, Sig, Resp, Evt]): List[ClassTag[?]]                     =
      extractMatchedClass(wio.evtHandler)
    override def onRunIO[Evt](wio: WIO.RunIO[IO, Ctx, In, Err, Out, Evt]): List[ClassTag[?]]                                                   =
      extractMatchedClass(wio.evtHandler)
    override def onNoop(wio: WIO.End[IO, Ctx]): List[ClassTag[?]]                                                                              = Nil
    override def onPure(wio: WIO.Pure[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                                               = Nil
    override def onTimer(wio: WIO.Timer[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                                             =
      extractMatchedClass(wio.startedEventHandler) ++ extractMatchedClass(wio.releasedEventHandler)
    override def onAwaitingTime(wio: WIO.AwaitingTime[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                               =
      extractMatchedClass(wio.releasedEventHandler)
    override def onExecuted[In1](wio: WIO.Executed[IO, Ctx, Err, Out, In1]): List[ClassTag[?]]                                                 = Nil
    override def onDiscarded[In1](wio: WIO.Discarded[IO, Ctx, In1]): List[ClassTag[?]]                                                         = Nil
    override def onRecovery[Evt](wio: WIO.Recovery[IO, Ctx, In, Err, Out, Evt]): List[ClassTag[?]]                                             =
      extractMatchedClass(wio.eventHandler)
    override def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[IO, Ctx, Err1, Err, Out1, Out, In]): List[ClassTag[?]]          = recurse(
      wio.base,
    )
    override def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[IO, Ctx, In, Err, Out, ErrIn, TempOut]): List[ClassTag[?]] =
      recurse(
        wio.base,
      )
    override def onRetry(wio: WIO.Retry[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                                             = recurse(wio.base)
    override def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[IO, Ctx, In1, Err1, Out1, In, Out, Err]): List[ClassTag[?]]   =
      recurse(
        wio.base,
      )
    override def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[IO, Ctx, In, ErrIn, Out, Err]): List[ClassTag[?]]                           =
      recurse(wio.base) ++ recurse(wio.handleError)
    override def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](
        wio: WIO.Loop[IO, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn],
    ): List[ClassTag[?]]                                                                                                                       = recurse(wio.body) ++ recurse(wio.onRestart)
    override def onFork(wio: WIO.Fork[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                                               = wio.branches.flatMap(b => recurse(b.wio)).toList
    override def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[IO, Ctx, In, Err, Out1, Out]): List[ClassTag[?]]                             =
      recurse(wio.first) ++ recurse(wio.second)
    override def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[IO, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): List[ClassTag[?]]                                                                                                                       = new ExpectedEventsVisitor(wio.inner).run
    override def onHandleInterruption(wio: WIO.HandleInterruption[IO, Ctx, In, Err, Out]): List[ClassTag[?]]                                   =
      recurse(wio.base) ++ recurse(wio.interruption)
    override def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[IO, Ctx, In, Err, Out, InterimState]): List[ClassTag[?]]           =
      wio.elements.flatMap(e => recurse(e.wio)).toList
    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[IO, Ctx, In, Err, Out1, Evt]): List[ClassTag[?]]                           =
      recurse(wio.base) ++ extractMatchedClass(wio.eventHandler)
    override def onForEach[Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[IO, Ctx, In, Err, Out, Elem, InnerCtx, ElemOut, InterimState],
    ): List[ClassTag[?]]                                                                                                                       = new ExpectedEventsVisitor(wio.elemWorkflow).run

    private def extractMatchedClass(eh: EventHandler[?, ?, ?, ?]): List[ClassTag[?]] = eh match {
      case te: TypedEventHandler[?, ?, ?, ?] => List(te.matchedClass)
      case _                                 => Nil
    }

    private def recurse(nextWio: WIO[IO, ?, ?, ?, Ctx]): List[ClassTag[?]] = new ExpectedEventsVisitor(nextWio).run
  }
}
