package workflows4s.wio

import cats.syntax.all.*
import workflows4s.wio.internal.WorkflowEmbedding

import scala.annotation.nowarn

object Interpreter {

  sealed trait EventResponse[F[_], Ctx <: WorkflowContext] {
    def newWorkflow: Option[WIO.Initial[F, Ctx]] = this match {
      case EventResponse.Ok(newFlow)       => newFlow.some
      // TODO event is silently ignored here and runtimes have to log it.
      //   Would be good to commonize this behavior
      case EventResponse.UnexpectedEvent() => None
    }
  }

  object EventResponse {
    case class Ok[F[_], Ctx <: WorkflowContext](newFlow: WIO.Initial[F, Ctx]) extends EventResponse[F, Ctx]
    case class UnexpectedEvent[F[_], Ctx <: WorkflowContext]()                extends EventResponse[F, Ctx]
  }

}

abstract class Visitor[F[_], Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[F, In, Err, Out, Ctx]) {
  type Result
  type State = WCState[Ctx]

  def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[F, Ctx, In, Out, Err, Sig, Resp, Evt]): Result
  def onRunIO[Evt](wio: WIO.RunIO[F, Ctx, In, Err, Out, Evt]): Result
  def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[F, Ctx, Err1, Err, Out1, Out, In]): Result
  def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[F, Ctx, In1, Err1, Out1, In, Out, Err]): Result
  def onNoop(wio: WIO.End[F, Ctx]): Result
  def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[F, Ctx, In, Err, Out, ErrIn, TempOut]): Result
  def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[F, Ctx, In, ErrIn, Out, Err]): Result
  def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[F, Ctx, In, Err, Out1, Out]): Result
  def onPure(wio: WIO.Pure[F, Ctx, In, Err, Out]): Result
  def onLoop[BodyIn <: WCState[Ctx], BodyOut <: WCState[Ctx], ReturnIn](wio: WIO.Loop[F, Ctx, In, Err, Out, BodyIn, BodyOut, ReturnIn]): Result
  def onFork(wio: WIO.Fork[F, Ctx, In, Err, Out]): Result
  def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
      wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
  ): Result
  def onHandleInterruption(wio: WIO.HandleInterruption[F, Ctx, In, Err, Out]): Result
  def onTimer(wio: WIO.Timer[F, Ctx, In, Err, Out]): Result
  def onAwaitingTime(wio: WIO.AwaitingTime[F, Ctx, In, Err, Out]): Result
  def onExecuted[In1](wio: WIO.Executed[F, Ctx, Err, Out, In1]): Result
  def onDiscarded[In1](wio: WIO.Discarded[F, Ctx, In1]): Result

  def onParallel[InterimState <: WCState[Ctx]](wio: WIO.Parallel[F, Ctx, In, Err, Out, InterimState]): Result
  def onCheckpoint[Evt, Out1 <: Out](wio: WIO.Checkpoint[F, Ctx, In, Err, Out1, Evt]): Result
  def onRecovery[Evt](wio: WIO.Recovery[F, Ctx, In, Err, Out, Evt]): Result
  def onRetry(wio: WIO.Retry[F, Ctx, In, Err, Out]): Result
  def onForEach[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
      wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
  ): Result

  @nowarn("msg=the type test for workflows4s.wio.WIO.Embedded")
  def run: Result = {
    wio match {
      case x: WIO.HandleSignal[F, ?, ?, ?, ?, ?, ?, ?]                    => onSignal(x)
      case x: WIO.RunIO[F, ?, ?, ?, ?, ?]                                 => onRunIO(x)
      // https://github.com/scala/scala3/issues/20040
      case x: WIO.FlatMap[F, ?, ? <: Err, Err, ? <: WCState[Ctx], ?, ?]   =>
        x match {
          case x: WIO.FlatMap[F, ?, err1, Err, out1, ?, In] => onFlatMap[out1, err1](x)
        }
      case x: WIO.Transform[F, ?, ?, ?, ? <: State, ?, ?, Err]            => onTransform(x)
      case x: WIO.End[F, ?]                                               => onNoop(x)
      case x: WIO.HandleError[F, ?, ?, ?, ?, ?, ? <: State]               => onHandleError(x)
      case x: WIO.AndThen[F, ?, ?, ?, ? <: State, ? <: State]             => onAndThen(x)
      case x: WIO.Pure[F, ?, ?, ?, ?]                                     => onPure(x)
      case x: WIO.HandleErrorWith[F, ?, ?, ?, ?, ?]                       => onHandleErrorWith(x)
      case x: WIO.Loop[F, ?, ?, ?, ? <: State, ? <: State, ? <: State, ?] => onLoop(x)
      case x: WIO.Fork[F, ?, ?, ?, ?]                                     => onFork(x)
      case x: WIO.Embedded[F, ?, ?, ?, ?, ?, ?]                           => onEmbedded(x.asInstanceOf) // TODO make compiler happy
      case x: WIO.HandleInterruption[F, ?, ?, ?, ?]                       => onHandleInterruption(x)
      case x: WIO.Timer[F, ?, ?, ?, ?]                                    => onTimer(x)
      case x: WIO.AwaitingTime[F, ?, ?, ?, ?]                             => onAwaitingTime(x)
      case x: WIO.Executed[F, ?, ?, ?, ?]                                 => onExecuted(x)
      case x: WIO.Discarded[F, ?, ?]                                      => onDiscarded(x)
      case x: WIO.Parallel[F, ?, ?, ?, ? <: State, ? <: State]            => onParallel(x)
      case x: WIO.Checkpoint[F, ?, ?, ?, ? <: State, ?]                   => onCheckpoint(x)
      case x: WIO.Recovery[F, ?, ?, ?, ?, ?]                              => onRecovery(x)
      case x: WIO.Retry[F, ?, ?, ?, ?]                                    => onRetry(x)
      case x: WIO.ForEach[F, ?, ?, ?, ?, ?, ?, ?, ?]                      => onForEach(x.asInstanceOf)  // TODO make compiler happy
    }
  }

  case class Matching[BranchIn](idx: Int, input: BranchIn, wio: WIO[F, BranchIn, Err, Out, Ctx])
  def selectMatching(wio: WIO.Fork[F, Ctx, In, Err, Out], in: In): Option[Matching[?]] = {
    wio.branches.zipWithIndex.collectFirstSome((branch, idx) => branch.condition(in).map(interm => Matching(idx, interm, branch.wio)))
  }

  def convertEmbeddingResult2[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], O1[_ <: WCState[InnerCtx]] <: WCState[Ctx]](
      wio: WIO.Embedded[F, Ctx, In, Err, InnerCtx, InnerOut, O1],
      newWf: WFExecution[F, InnerCtx, In, Err, InnerOut],
      input: In,
  ): WFExecution[F, Ctx, In, Err, Out] = {
    // we are interpretting WIO.Embedded and by definition its Out = MappingOutput[InnerOut]. Its just compiler forgetting it somehow
    def convert(x: WFExecution[F, Ctx, In, Err, O1[InnerOut]]): WFExecution[F, Ctx, In, Err, Out] = x.asInstanceOf
    newWf match {
      case WFExecution.Complete(newWio) =>
        convert(
          WFExecution.complete(
            wio.copy(inner = newWio),
            newWio.output.map(wio.embedding.convertState(_, input)),
            input,
            newWio.index,
          ),
        )
      case WFExecution.Partial(newWio)  =>
        val embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, O1, Any] = wio.embedding.contramap(_ => input)
        convert(WFExecution.Partial(WIO.Embedded(newWio, embedding)))
    }
  }

  def convertForEachResult[ElemId, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
      wio: WIO.ForEach[F, Ctx, In, Err, Out, ElemId, InnerCtx, ElemOut, InterimState],
      newWf: WFExecution[F, InnerCtx, Any, Err, ElemOut],
      input: In,
      elemId: ElemId,
  ): WFExecution[F, Ctx, In, Err, Out] = {
    val newState   = wio.state(input).updated(elemId, newWf.wio)
    val newForEach = wio.copy(stateOpt = Some(newState))
    newWf match {
      case WFExecution.Complete(newWio) =>
        val completedStates: Map[ElemId, ElemOut] = newState.flatMap(x => x._2.asExecuted.flatMap(_.output.toOption).tupleLeft(x._1))
        if completedStates.size == newState.size then {
          val output = wio.buildOutput(input, completedStates)
          WFExecution.complete(newForEach, output.asRight, input, newWio.index + 1)
        } else {
          newWio.output match {
            case Left(err) => WFExecution.complete(newForEach, Left(err), input, newWio.index)
            case Right(_)  => WFExecution.Partial(newForEach)
          }
        }
      case WFExecution.Partial(_)       => WFExecution.Partial(newForEach)
    }
  }

}

sealed trait WFExecution[F[_], C <: WorkflowContext, -I, +E, +O <: WCState[C]] {
  def wio: WIO[F, I, E, O, C]
}

object WFExecution {

  case class Complete[F[_], C <: WorkflowContext, E, O <: WCState[C], I](wio: WIO.Executed[F, C, E, O, I]) extends WFExecution[F, C, I, E, O]

  case class Partial[F[_], C <: WorkflowContext, I, E, O <: WCState[C]](wio: WIO[F, I, E, O, C]) extends WFExecution[F, C, I, E, O]

  def complete[F[_], Ctx <: WorkflowContext, Err, Out <: WCState[Ctx], In](
      original: WIO[F, In, ?, ?, Ctx],
      output: Either[Err, Out],
      input: In,
      index: Int,
      event: Option[WCEvent[Ctx]] = None,
  ) =
    WFExecution.Complete(WIO.Executed(original, output, input, index, event))

}
