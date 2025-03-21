package workflows4s.wio.internal

import cats.implicits.{catsSyntaxOptionId, toFunctorOps}
import workflows4s.wio.*

object GetStateEvaluator {

  def extractLastState[Ctx <: WorkflowContext, In](
      wio: WIO[In, ?, WCState[Ctx], Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ): Option[WCState[Ctx]] = {
    val visitor = new GetStateVisitor(wio, input, lastSeenState)
    visitor.run
  }

  private class GetStateVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = Option[WCState[Ctx]]

    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = wio.output.toOption

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                           = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = None

    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result                                                           = recurse(wio.original, wio.input)
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input)
    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          =
      recurse(wio.base, wio.contramapInput(input))
    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                   = {
      val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
      recurse(wio.current, input, lastState).orElse(wio.history.lastOption.flatMap(recurse(_, ())))
    }
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      => None
            case Right(value) => recurse(wio.second, value, value).getOrElse(value).some
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err)    => recurse(wio.handleError, (lastSeenState, err)).orElse(recurse(wio.base, input))
            case Right(value) => value.some
          }
        case None               => recurse(wio.base, input)
      }
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                             = {
      wio.selected match {
        case Some(selectedIdx) =>
          val branch = wio.branches(selectedIdx)
          recurse(branch.wio(), branch.condition(input).get)
        case None              => None
      }
    }
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      recurse(wio.interruption, lastSeenState).orElse(recurse(wio.base, input))
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val lastStateAsInner = wio.embedding.unconvertStateUnsafe(lastSeenState)
      GetStateVisitor(wio.inner, input, lastStateAsInner).run
        .map(innerState => wio.embedding.convertState(innerState, input))
    }

    def onParallel[InterimState <: workflows4s.wio.WorkflowContext.State[Ctx]](
        wio: workflows4s.wio.WIO.Parallel[Ctx, In, Err, Out, InterimState],
    ): Result = {
      val subStates = wio.elements.flatMap(elem => recurse(elem.wio, input).tupleLeft(elem))
      if (subStates.isEmpty) None
      else {
        val initialInterim = wio.initialInterimState(input)
        subStates
          .foldLeft(initialInterim)({ case (interim, (elem, partial)) => elem.incorporateState(interim, partial) })
          .some
      }
    }

    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[Ctx, In, Err, Out1, Evt]): Result = recurse(wio.base, input)
    override def onRecovery[Evt](wio: WIO.Recovery[Ctx, In, Err, Out, Evt]): Result                   = None

    def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[I1, ?, ?, Ctx],
        input: I1,
        state: WCState[Ctx] = lastSeenState,
    ): GetStateVisitor[Ctx, I1, E1, O1]#Result =
      new GetStateVisitor(wio, input, state).run

  }
}
