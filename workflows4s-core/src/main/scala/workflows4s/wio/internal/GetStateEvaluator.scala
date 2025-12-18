package workflows4s.wio.internal

import cats.implicits.{catsSyntaxOptionId, toFunctorOps}
import workflows4s.wio.*

object GetStateEvaluator {

  /** Added F[_] and explicit type parameters to help the compiler reduce match types.
    */
  def extractLastState[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ): Option[WCState[Ctx]] = {
    val visitor = new GetStateVisitor[F, Ctx, In, Err, Out](wio, input, lastSeenState)
    visitor.run
  }

  private class GetStateVisitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[F, In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends Visitor[F, Ctx, In, Err, Out](wio) {
    override type Result = Option[WCState[Ctx]]

    def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result = wio.output match {
      case Left(_)      => recurse(wio.original, wio.input)
      case Right(value) => value.some
    }

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[F, Ctx]): Result                                                          = None
    def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result                                           = None
    def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result                           = None

    def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result                                                         = recurse(wio.original, wio.input)
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result          = recurse(wio.base, input)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result = recurse(wio.base, input)
    override def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Option[WCState[Ctx]]                                      = recurse(wio.base, input)
    override def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result                  = recurse(wio.base, input)

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input))

    def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result = {
      val lastState = wio.history.lastOption.flatMap(_.output.toOption).getOrElse(lastSeenState)
      recurse(wio.current.wio, input, lastState).orElse(wio.history.lastOption.flatMap(recurse(_, ())))
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(_)      => recurse(wio.first, input)
            case Right(value) => recurse(wio.second, value, value).getOrElse(value).some
          }
        case None                => recurse(wio.first, input)
      }
    }

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err)    => recurse(wio.handleError, (lastSeenState, err)).orElse(recurse(wio.base, input))
            case Right(value) => value.some
          }
        case None               => recurse(wio.base, input)
      }
    }

    def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result = {
      wio.selected match {
        case Some(selectedIdx) =>
          val branch = wio.branches(selectedIdx)
          recurse(branch.wio, branch.condition(input).get)
        case None              => None
      }
    }

    def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result = {
      recurse(wio.interruption, lastSeenState).orElse(recurse(wio.base, input))
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
        wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val lastStateAsInner = wio.embedding.unconvertStateUnsafe(lastSeenState)
      new GetStateVisitor(wio.inner, input, lastStateAsInner).run
        .map(innerState => wio.embedding.convertState(innerState, input))
    }

    def onParallel[InterimState <: WCState[Ctx]](
        wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState],
    ): Result = {
      val subStates = wio.elements.flatMap(elem => recurse(elem.wio, input).tupleLeft(elem))
      if subStates.isEmpty then None
      else {
        val initialInterim = wio.initialInterimState(input)
        subStates
          .foldLeft(initialInterim)({ case (interim, (elem, partial)) => elem.incorporateState(interim, partial) })
          .some
      }
    }

    override def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result = None

    override def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
        wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
    ): Result = {
      val state = wio.state(input).flatMap((elemId, elemWio) => new GetStateVisitor(elemWio, (), wio.initialElemState()).run.tupleLeft(elemId))
      if state.isEmpty then None
      else Some(wio.interimState(input))
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](
        wio: WIO[F, I1, E1, O1, Ctx],
        input: I1,
        state: WCState[Ctx] = lastSeenState,
    ): Result =
      new GetStateVisitor(wio, input, state).run
  }
}
