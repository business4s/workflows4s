package workflows4s.wio.internal

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.Interpreter.EventResponse
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus

object EventEvaluator {

  def handleEvent[Ctx <: WorkflowContext](
      event: WCEvent[Ctx],
      wio: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
  ): EventResponse[Ctx] = {
    val visitor: EventVisitor[Ctx, Any, Nothing, WCState[Ctx]] = new EventVisitor(wio, event, state, state)
    visitor.run
      .map(wf => wf.toActiveWorkflow(state))
      .map(EventResponse.Ok(_))
      .getOrElse(EventResponse.UnexpectedEvent())
  }

  private class EventVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      event: WCEvent[Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
  ) extends Visitor[Ctx, In, Err, Out](wio) {
    type NewWf           = WFExecution[Ctx, In, Err, Out]
    override type Result = Option[NewWf]

    def doHandle[Evt](handler: EventHandler[In, Either[Err, Out], WCEvent[Ctx], Evt]): Result =
      handler
        .detect(event)
        .map(x => WFExecution.complete(wio, handler.handle(input, x), input))

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = doHandle(wio.evtHandler.map(_._1))
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = doHandle(wio.evtHandler)
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = doHandle(wio.releasedEventHandler)

    def onNoop(wio: WIO.End[Ctx]): Result                              = None
    def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result           = None
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result               = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result =
      recurse(wio.base, input, event, 0).map(processFlatMap(wio, _))

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input), event, 0).map(processTransform(wio, _, input))

    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err) =>
              onHandleErrorWith(WIO.HandleErrorWith(baseExecuted, wio.handleError(lastSeenState, err), wio.handledErrorMeta, wio.newErrorMeta))
            case Right(_)  =>
              // this should never happen,
              // if base was successfuly executed, we should never again end up evaluating handle error
              // TODO better exception
              ???
          }
        case None               => recurse(wio.base, input, event, 0).map(processHandleErrorBase(wio, _, lastSeenState))
      }
    }

    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err)    => recurse(wio.handleError, (lastSeenState, err), event, 1).map(processHandleErrorWithHandler(wio, _, input))
            case Right(value) => WFExecution.complete(wio, Right(value), input).some
          }
        case None               => recurse(wio.base, input, event, 0).map(processHandleErrorWith_Base(wio, _, input))
      }
    }

    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(err)    => WFExecution.complete(wio, Left(err), input).some
            case Right(value) =>
              recurse(wio.second, value, event, 0)
                .map({
                  case WFExecution.Complete(newWio) => WFExecution.complete(WIO.AndThen(wio.first, newWio), newWio.output, input)
                  case WFExecution.Partial(newWio)  => WFExecution.Partial(WIO.AndThen(firstExecuted, newWio))
                })
          }
        case None                =>
          recurse(wio.first, input, event, 0)
            .map({
              case WFExecution.Complete(newWio) => WFExecution.Partial(WIO.AndThen(newWio, wio.second))
              case WFExecution.Partial(newWio)  => WFExecution.Partial(WIO.AndThen(newWio, wio.second))
            })
      }
    }

    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result = {
      recurse(wio.current, input, event, 0).map(processLoop(wio, _, input))
    }

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result = {
      def updateSelectedBranch[I](selectedIdx: Int, newWio: WIO[I, Err, Out, Ctx]): WIO.Fork[Ctx, In, Err, Out] = {
        wio.copy(branches = wio.branches.zipWithIndex.map((branch, idx) => {
          // TODO is hould be doable to avoid casting here.
          //  (the code is correct, but its safer to prove it to the compiler)
          if (idx == selectedIdx) WIO.Branch(branch.condition, newWio.asInstanceOf[WIO[branch.I, Err, Out, Ctx]], branch.name)
          else branch.discard
        }))
      }
      wio.selected match {
        case Some(selectedIdx) =>
          val branch    = wio.branches(selectedIdx)
          val branchOut = branch.condition(input).get
          recurse(branch.wio, branchOut, event, selectedIdx).map({
            case WFExecution.Complete(wio) => WFExecution.complete(updateSelectedBranch(selectedIdx, wio), wio.output, input)
            case WFExecution.Partial(wio)  => WFExecution.Partial(updateSelectedBranch(selectedIdx, wio))
          })
        case None              =>
          selectMatching(wio, input).flatMap({ case (wio, selectedIdx) =>
            recurse(wio, input, event, selectedIdx).map({
              case WFExecution.Complete(wio) => WFExecution.complete(updateSelectedBranch(selectedIdx, wio), wio.output, input)
              case WFExecution.Partial(wio)  => WFExecution.Partial(updateSelectedBranch(selectedIdx, wio))
            })
          })
      }
    }

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      wio.embedding
        .unconvertEvent(event)
        .flatMap(convertedEvent => new EventVisitor(wio.inner, convertedEvent, input, newState).run)
        .map(convertEmbeddingResult2(wio, _, input))
    }

    // will be problematic if we use the same event on both paths
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def runBase: Result         = recurse(wio.base, input, event, 0)
        .map(processHandleInterruption_Base(wio, _))
      def runInterruption: Result = recurse(wio.interruption, lastSeenState, event, 1)
        .map(processHandleInterruption_Interruption(wio, _, input))

      wio.status match {
        case InterruptionStatus.Interrupted  => runInterruption
        case InterruptionStatus.TimerStarted => runInterruption.orElse(runBase)
        case InterruptionStatus.Pending      => runInterruption.orElse(runBase)
      }
    }

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result = {
      wio.startedEventHandler
        .detect(event)
        .map(started => {
          val releaseTime = wio.getReleaseTime(started, input)
          WIO.AwaitingTime(releaseTime, wio.releasedEventHandler)
        })
        .map(WFExecution.Partial(_))
    }

    // TODO not entirely correct, need to udpate lastSeenState ?
    def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1, e: WCEvent[Ctx], idx: Int): EventVisitor[Ctx, I1, E1, O1]#Result =
      new EventVisitor(wio, e, s, lastSeenState).run

  }
}
