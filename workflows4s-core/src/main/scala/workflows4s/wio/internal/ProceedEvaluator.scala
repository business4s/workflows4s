package workflows4s.wio.internal

import java.time.Instant
import cats.syntax.all.*
import workflows4s.wio.*
import workflows4s.wio.WIO.HandleInterruption.InterruptionStatus
import workflows4s.wio.model.WIOId

// For the given workflow tries to move it to next step if possible without executing any side-effecting comptations.
// This is most common in presence of `Pure` or timers awaiting the threshold.
object ProceedEvaluator {

  // runIO required to eliminate Pures showing up after FlatMap
  def proceed[Ctx <: WorkflowContext](
      wio: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      now: Instant,
  ): Response[Ctx] = {
    val visitor: ProceedVisitor[Ctx, Any, Nothing, WCState[Ctx]] = new ProceedVisitor(wio, state, state, now, WIOId.root)
    Response(visitor.run.map(_.toActiveWorkflow(state)))
  }

  case class Response[Ctx <: WorkflowContext](newFlow: Option[ActiveWorkflow[Ctx]])

  private class ProceedVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
      wio: WIO[In, Err, Out, Ctx],
      input: In,
      lastSeenState: WCState[Ctx],
      now: Instant,
      id: WIOId,
  ) extends Visitor[Ctx, In, Err, Out](wio, id) {
    type NewWf           = WFExecution[Ctx, In, Err, Out]
    override type Result = Option[NewWf]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result = None
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                               = None
    def onNoop(wio: WIO.End[Ctx]): Result                                                          = None
    def onExecuted(wio: WIO.Executed[Ctx, Err, Out]): Result                                       = None
    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result                                         = None
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result                           = None
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result                                       = None

    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result =
      recurse(wio.base, input, 0).map(processFlatMap(wio, _))

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result =
      recurse(wio.base, wio.contramapInput(input), 0).map(processTransform(wio, _, input))

    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result =
      recurse(wio.base, input, 0).map(processNamed(wio, _))

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
        case None               => recurse(wio.base, input, 0).map(processHandleErrorBase(wio, _, lastSeenState))
      }
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           = {
      wio.base.asExecuted match {
        case Some(baseExecuted) =>
          baseExecuted.output match {
            case Left(err)    => recurse(wio.handleError, (lastSeenState, err), 1).map(processHandleErrorWithHandler(wio, _, baseExecuted))
            case Right(value) => WFExecution.complete(wio, Right(value)).some
          }
        case None               => recurse(wio.base, input, 0).map(processHandleErrorWith_Base(wio, _))
      }
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                             = {
      wio.first.asExecuted match {
        case Some(firstExecuted) =>
          firstExecuted.output match {
            case Left(err)    => WFExecution.complete(wio, Left(err)).some
            case Right(value) =>
              recurse(wio.second, value, 0).map({
                case WFExecution.Complete(newWio) => WFExecution.complete(WIO.AndThen(wio.first, newWio), newWio.output)
                case WFExecution.Partial(newWio)  => WFExecution.Partial(WIO.AndThen(firstExecuted, newWio))
              })
          }
        case None                =>
          recurse(wio.first, input, 0).map(result => WFExecution.Partial(WIO.AndThen(result.wio, wio.second)))
      }
    }

    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result =
      WFExecution.complete(wio, wio.value(input)).some

    def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result =
      recurse(wio.current, input, wio.history.size).map(processLoop(wio, _))

    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result =
      selectMatching(wio, input).flatMap({ case (nextWio, idx) => recurse(nextWio, input, idx) })

    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result = {
      val newState: WCState[InnerCtx] =
        wio.embedding
          .unconvertState(lastSeenState)
          .getOrElse(
            wio.initialState(input),
          ) // TODO, this is not safe, we will use initial state if the state mapping is incorrect (not symetrical). This will be very hard for the user to diagnose.
      new ProceedVisitor(wio.inner, input, newState, now, id.child(0)).run
        .map(convertEmbeddingResult2(wio, _, input))
    }

    // proceed on interruption will be needed for timeouts
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result = {
      def runBase: Result         = recurse(wio.base, input, 0)
        .map(processHandleInterruption_Base(wio, _))
      val runInterruption: Result = recurse(wio.interruption, lastSeenState, 1)
        .map(processHandleInterruption_Interruption(wio, _, input))

      wio.status match {
        case InterruptionStatus.Interrupted  => runInterruption
        case InterruptionStatus.TimerStarted => runInterruption.orElse(runBase)
        case InterruptionStatus.Pending      => runInterruption.orElse(runBase)
      }
    }

    private def recurse[I1, E1, O1 <: WCState[Ctx]](wio: WIO[I1, E1, O1, Ctx], s: I1, idx: Int): Option[WFExecution[Ctx, I1, E1, O1]] =
      new ProceedVisitor(wio, s, lastSeenState, now, id.child(0)).run
  }

}
