package workflows4s.wio

import cats.effect.IO
import cats.syntax.all.*
import workflows4s.wio.WIO.HandleInterruption.{InterruptionStatus, InterruptionType}
import workflows4s.wio.internal.WorkflowEmbedding

object Interpreter {

  sealed trait EventResponse[Ctx <: WorkflowContext] {
    def newWorkflow: Option[ActiveWorkflow[Ctx]] = this match {
      case EventResponse.Ok(newFlow)       => newFlow.some
      case EventResponse.UnexpectedEvent() => None
    }
  }

  object EventResponse {
    case class Ok[Ctx <: WorkflowContext](newFlow: ActiveWorkflow[Ctx]) extends EventResponse[Ctx]
    case class UnexpectedEvent[Ctx <: WorkflowContext]()                extends EventResponse[Ctx]

    def fromOption[Ctx <: WorkflowContext](o: Option[ActiveWorkflow[Ctx]]): EventResponse[Ctx] = o match {
      case Some(value) => Ok(value)
      case None        => UnexpectedEvent()
    }
  }

  sealed trait ProceedResponse[Ctx <: WorkflowContext] {
    def newWorkflow: Option[ActiveWorkflow[Ctx]] = this match {
      case ProceedResponse.Executed(newFlow) => newFlow.some
      case ProceedResponse.Noop()            => none
    }
  }

  object ProceedResponse {
    case class Executed[Ctx <: WorkflowContext](newFlow: ActiveWorkflow[Ctx]) extends ProceedResponse[Ctx]
    case class Noop[Ctx <: WorkflowContext]()                                 extends ProceedResponse[Ctx]
  }

  sealed trait RunIOResponse[Ctx <: WorkflowContext] {
    def event: Option[IO[WCEvent[Ctx]]] = this match {
      case RunIOResponse.Executed(newFlow) => newFlow.some
      case RunIOResponse.Noop()            => None
    }
  }

  object RunIOResponse {
    case class Executed[Ctx <: WorkflowContext](newFlow: IO[WCEvent[Ctx]]) extends RunIOResponse[Ctx]
    case class Noop[Ctx <: WorkflowContext]()                              extends RunIOResponse[Ctx]
  }

  sealed trait SignalResponse[Ctx <: WorkflowContext, Resp]

  object SignalResponse {
    case class Ok[Ctx <: WorkflowContext, Resp](value: IO[(WCEvent[Ctx], Resp)]) extends SignalResponse[Ctx, Resp]
    case class UnexpectedSignal[Ctx <: WorkflowContext, Resp]()                  extends SignalResponse[Ctx, Resp]
  }
}

abstract class Visitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx]) {
  type Result
  type State = WCState[Ctx]

  def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result
  def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result
  def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result
  def onTransform[In1, Out1 <: WCState[Ctx], Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result
  def onNoop(wio: WIO.End[Ctx]): Result
  def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result
  def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result
  def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result
  def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result
  def onLoop[Out1 <: WCState[Ctx]](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result
  def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result
  def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
      wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
  ): Result
  def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result
  def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result
  def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result
  def onExecuted[In1](wio: WIO.Executed[Ctx, Err, Out, In1]): Result
  def onDiscarded[In1](wio: WIO.Discarded[Ctx, In1]): Result

  def run: Result = {
    wio match {
      case x: WIO.HandleSignal[?, ?, ?, ?, ?, ?, ?]                  => onSignal(x)
      case x: WIO.RunIO[?, ?, ?, ?, ?]                               => onRunIO(x)
      // https://github.com/scala/scala3/issues/20040
      case x: WIO.FlatMap[?, ? <: Err, Err, ? <: WCState[Ctx], ?, ?] =>
        x match {
          case x: WIO.FlatMap[?, err1, Err, out1, ?, In] => onFlatMap[out1, err1](x)
        }
      case x: WIO.Transform[?, ?, ?, ? <: State, ?, ?, Err]          => onTransform(x)
      case x: WIO.End[?]                                             => onNoop(x)
      case x: WIO.HandleError[?, ?, ?, ?, ?, ? <: State]             => onHandleError(x)
      case x: WIO.AndThen[?, ?, ?, ? <: State, ? <: State]           => onAndThen(x)
      case x: WIO.Pure[?, ?, ?, ?]                                   => onPure(x)
      case x: WIO.HandleErrorWith[?, ?, ?, ?, ?]                     => onHandleErrorWith(x)
      case x: WIO.Loop[?, ?, ?, ? <: State, ? <: State]              => onLoop(x)
      case x: WIO.Fork[?, ?, ?, ?]                                   => onFork(x)
      case x: WIO.Embedded[?, ?, ?, ?, ?, ?]                         =>
        x match {
          case x: WIO.Embedded[?, ?, ?, ic, ?, ?] =>
            x match {
              case x: WIO.Embedded[?, ?, ?, ?, ? <: WCState[ic], ?] =>
                x match {
                  case x: WIO.Embedded[?, ?, ?, ?, io, mp] => onEmbedded(x.asInstanceOf) // TODO
                }
            }
        }
      case x: WIO.HandleInterruption[?, ?, ?, ?]                     => onHandleInterruption(x)
      case x: WIO.Timer[?, ?, ?, ?]                                  => onTimer(x)
      case x: WIO.AwaitingTime[?, ?, ?, ?]                           => onAwaitingTime(x)
      case x: WIO.Executed[?, ?, ?, ?]                               => onExecuted(x)
      case x: WIO.Discarded[?, ?]                                    => onDiscarded(x)
    }
  }

  protected def processFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](
      wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In],
      baseResult: WFExecution[Ctx, In, Err1, Out1],
  ): WFExecution[Ctx, In, Err, Out] = {
    baseResult match {
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(err)    => WFExecution.complete(wio.copy(base = newWio), Left(err), newWio.input)
          case Right(value) => WFExecution.Partial(WIO.AndThen(newWio, wio.getNext(value)))
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
    }
  }

  def processTransform[Out1 <: WCState[Ctx], In1, Err1](
      transform: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err],
      baseResult: WFExecution[Ctx, In1, Err1, Out1],
      input: In,
  ): WFExecution[Ctx, In, Err, Out] = {
    baseResult match {
      case WFExecution.Complete(newWio) =>
        WFExecution.complete(
          WIO.Transform(newWio, transform.contramapInput, transform.mapOutput),
          transform.mapOutput(input, newWio.output),
          input,
        )
      case WFExecution.Partial(wio)     =>
        WFExecution.Partial[Ctx, In, Err, Out](WIO.Transform(wio, transform.contramapInput, (_, out) => transform.mapOutput(input, out)))
    }
  }

  protected def processHandleErrorBase[ErrIn, TempOut <: WCState[Ctx]](
      wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut],
      baseResult: WFExecution[Ctx, In, ErrIn, Out],
      state: WCState[Ctx],
  ): WFExecution[Ctx, In, Err, Out] = {
    baseResult match {
      case WFExecution.Complete(executedBase) =>
        executedBase.output match {
          case Left(err)    =>
            WFExecution.Partial(WIO.HandleErrorWith(executedBase, wio.handleError(state, err), wio.handledErrorMeta, wio.newErrorMeta))
          case Right(value) => WFExecution.complete(wio.copy(base = executedBase), Right(value), executedBase.input)
        }
      case WFExecution.Partial(newWio)        => WFExecution.Partial(wio.copy(base = newWio))
    }
  }

  protected def processHandleErrorWith_Base[ErrIn](
      wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err],
      baseResult: WFExecution[Ctx, In, ErrIn, Out],
      input: In,
  ): WFExecution[Ctx, In, Err, Out] = {
    def updateBase(newBase: WIO[In, ErrIn, Out, Ctx]) = wio.copy(base = newBase)
    baseResult match {
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(_)      => WFExecution.Partial(updateBase(newWio))
          case Right(value) => WFExecution.complete(updateBase(newWio), Right(value), input)
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(updateBase(newWio))
    }
  }

  protected def processHandleErrorWithHandler[ErrIn](
      wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err],
      handlerResult: WFExecution[Ctx, (WCState[Ctx], ErrIn), Err, Out],
      input: In,
      // its already part of `wio` but we need a prove the input is Any
  ): WFExecution[Ctx, In, Err, Out] = {
    def updateHandler(newHandler: WIO[(WCState[Ctx], ErrIn), Err, Out, Ctx]) = wio.copy(handleError = newHandler)
    handlerResult match {
      case WFExecution.Complete(newHandler) => WFExecution.complete(updateHandler(newHandler), newHandler.output, input)
      case WFExecution.Partial(newHandler)  => WFExecution.Partial(updateHandler(newHandler))
    }
  }

  def processLoop[LoopOut <: WCState[Ctx]](
      wio: WIO.Loop[Ctx, In, Err, LoopOut, Out],
      currentResult: WFExecution[Ctx, In, Err, LoopOut],
      input: In,
  ): WFExecution[Ctx, In, Err, Out] = {
    // TODO all the `.provideInput` here are not good, they enlarge the graph unnecessarly.
    //  alternatively we could maybe take the input from the last history entry
    currentResult match {
      case WFExecution.Complete(newWio) =>
        newWio.output match {
          case Left(err)    => WFExecution.complete(wio.copy(history = wio.history :+ newWio), Left(err), input)
          case Right(value) =>
            if (wio.isReturning) {
              WFExecution.Partial(wio.copy(current = wio.loop.provideInput(value), isReturning = false, history = wio.history :+ newWio))
            } else {
              wio.stopCondition(value) match {
                case Some(value) => WFExecution.complete(wio.copy(history = wio.history :+ newWio), Right(value), input)
                case None        =>
                  wio.onRestart match {
                    case Some(onRestart) =>
                      WFExecution.Partial(wio.copy(current = onRestart.provideInput(value), isReturning = true, history = wio.history :+ newWio))
                    case None            =>
                      WFExecution.Partial(wio.copy(current = wio.loop.provideInput(value), isReturning = true, history = wio.history :+ newWio))
                  }

              }
            }
        }
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(current = newWio))
    }
  }

  def selectMatching(wio: WIO.Fork[Ctx, In, Err, Out], in: In): Option[(WIO[In, Err, Out, Ctx], Int)] = {
    wio.branches.zipWithIndex.collectFirstSome(b => b._1.condition(in).map(interm => b._1.wio.transformInput[In](s => interm) -> b._2))
  }

  def convertEmbeddingResult2[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], O1[_] <: WCState[Ctx]](
      wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, O1],
      newWf: WFExecution[InnerCtx, In, Err, InnerOut],
      input: In,
  ): WFExecution[Ctx, In, Err, Out] = {
    // we are interpretting WIO.Embedded and by definition its Out = MappingOutput[InnerOut]. Its just compiler forgetting it somehow
    def convert(x: WFExecution[Ctx, In, Err, O1[InnerOut]]): WFExecution[Ctx, In, Err, Out] = x.asInstanceOf
    newWf match {
      case WFExecution.Complete(newWio) =>
        convert(
          WFExecution.complete(
            WIO.Embedded(newWio, wio.embedding, wio.initialState),
            newWio.output.map(wio.embedding.convertState(_, input)),
            input,
          ),
        )
      case WFExecution.Partial(newWio)  =>
        val embedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, O1, Any] = wio.embedding.contramap(_ => input)
        convert(WFExecution.Partial(WIO.Embedded(newWio, embedding, _ => wio.initialState(input))))
    }
  }

  def processHandleInterruption_Base(
      wio: WIO.HandleInterruption[Ctx, In, Err, Out],
      baseResult: WFExecution[Ctx, In, Err, Out],
  ): WFExecution[Ctx, In, Err, Out] = {
    baseResult match {
      case WFExecution.Complete(newWio) => WFExecution.complete(wio.copy(base = newWio), newWio.output, newWio.input)
      case WFExecution.Partial(newWio)  => WFExecution.Partial(wio.copy(base = newWio))
    }
  }
  def processHandleInterruption_Interruption(
      wio: WIO.HandleInterruption[Ctx, In, Err, Out],
      interruptionResult: WFExecution[Ctx, WCState[Ctx], Err, Out],
      input: In,
  ): WFExecution[Ctx, In, Err, Out] = {
    val newStatus: InterruptionStatus.Interrupted.type | InterruptionStatus.TimerStarted.type =
      wio.status match {
        case InterruptionStatus.Interrupted  => InterruptionStatus.Interrupted
        case InterruptionStatus.TimerStarted => InterruptionStatus.Interrupted
        case InterruptionStatus.Pending      =>
          wio.interruptionType match {
            case InterruptionType.Signal => InterruptionStatus.Interrupted
            case InterruptionType.Timer  => InterruptionStatus.TimerStarted
          }
      }
    interruptionResult match {
      case WFExecution.Complete(newInterruptionWio) =>
        WFExecution.complete(wio.copy(interruption = newInterruptionWio, status = newStatus), newInterruptionWio.output, input)
      case WFExecution.Partial(newInterruptionWio)  =>
        val newBase = newStatus match {
          case InterruptionStatus.Interrupted  => WIO.Discarded(wio.base, input)
          case InterruptionStatus.TimerStarted => wio.base.provideInput(input)
        }
        WFExecution.Partial(wio.copy(base = newBase, newInterruptionWio, status = newStatus))
    }
  }

  extension [I, E, O <: WCState[C], C <: WorkflowContext](wio: WIO[I, E, O, C]) {
    def asExecuted: Option[WIO.Executed[C, E, O, ?]] = wio match {
      case x: WIO.Executed[C, E, O, ?] => x.some
      case _                           => None
    }
  }

}

sealed trait WFExecution[C <: WorkflowContext, -I, +E, +O <: WCState[C]] {
  def wio: WIO[I, E, O, C]
}

object WFExecution {

  extension [C <: WorkflowContext](wfe: WFExecution[C, Any, Nothing, WCState[C]]) {
    def toActiveWorkflow(initialState: WCState[C]): ActiveWorkflow[C] = {
      ActiveWorkflow(wfe.wio, initialState)
    }
  }
  case class Complete[C <: WorkflowContext, E, O <: WCState[C], I](wio: WIO.Executed[C, E, O, I]) extends WFExecution[C, I, E, O]

  case class Partial[C <: WorkflowContext, I, E, O <: WCState[C]](wio: WIO[I, E, O, C]) extends WFExecution[C, I, E, O]

  def complete[Ctx <: WorkflowContext, Err, Out <: WCState[Ctx], In](original: WIO[In, ?, ?, Ctx], output: Either[Err, Out], input: In) =
    WFExecution.Complete(WIO.Executed(original, output, input))

}
