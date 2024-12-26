package workflows4s.wio

import java.time.Instant

import scala.annotation.nowarn

import cats.effect.IO
import cats.syntax.all.*
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

import workflows4s.wio.NextWfState.{NewBehaviour, NewValue}
abstract class Visitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx]) {
  type Result
  type State = WCState[Ctx]

  def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result
  def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result
  def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result
  def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result
  def onNoop(wio: WIO.Noop[Ctx]): Result
  def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result
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

  def run: Result = {
    wio match {
      case x: WIO.HandleSignal[?, ?, ?, ?, ?, ?, ?]                  => onSignal(x)
      case x: WIO.RunIO[?, ?, ?, ?, ?]                               => onRunIO(x)
      // https://github.com/scala/scala3/issues/20040
      case x: WIO.FlatMap[?, ? <: Err, Err, ? <: WCState[Ctx], ?, ?] =>
        x match {
          case x: WIO.FlatMap[?, err1, Err, out1, ?, In] => onFlatMap[out1, err1](x)
        }
      case x: WIO.Map[?, ?, Err, ? <: State, ?, ?]                   => onMap(x)
      case x: WIO.Noop[?]                                            => onNoop(x)
      case x: WIO.HandleError[?, ?, ?, ?, ?, ? <: State]             => onHandleError(x)
      case x: WIO.Named[?, ?, ?, ?]                                  => onNamed(x)
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
      case x: WIO.Timer[Ctx, In, Err, Out]                           => onTimer(x)
      case x: WIO.AwaitingTime[?, ?, ?, ?]                           => onAwaitingTime(x)
    }
  }

  protected def preserveFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](
      wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In],
      wf: NextWfState[Ctx, Err1, Out1],
  ): NextWfState[Ctx, Err, Out] = {
    wf.fold(
      b => {
        b.state match {
          case Left(err)    =>
            // TODO this should be safe but we somehow lost information that state produces from evaluating Flatmap.base has Error = Err1
            val errCasted = err.asInstanceOf[Err]
            NewValue(errCasted.asLeft)
          case Right(value) =>
            val effectiveWIO: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, b.State] = WIO.FlatMap(b.wio, (x: Out1) => wio.getNext(x), wio.errorMeta)
            NewBehaviour(effectiveWIO, b.state, b.wakeupAt)
        }
      },
      v => {
        v.value match {
          case Left(err)     => NewValue(Left(err))
          case Right(output) => NewBehaviour(wio.getNext(output), Right(output), None)
        }
      },
    )
  }

  protected def preserveAndThen[Out1 <: WCState[Ctx]](
      wio: WIO.AndThen[Ctx, In, Err, Out1, Out],
      wf: NextWfState[Ctx, Err, Out1],
  ): NextWfState[Ctx, Err, Out] =
    wf.fold(
      b => {
        val newWIO = WIO.AndThen(b.wio, wio.second)
        NewBehaviour(newWIO, b.state, b.wakeupAt)
      },
      v => {
        NewBehaviour(wio.second, v.value, None) // we ignore error, might backfire
      },
    )

  def preserveMap[Out1 <: WCState[Ctx], In1](
      wio: WIO.Map[Ctx, In1, Err, Out1, In, Out],
      wf: NextWfState[Ctx, Err, Out1],
      initState: In,
  ): NextWfState[Ctx, Err, Out] = {
    wf.fold[NextWfState[Ctx, Err, Out]](
      b => {
        val newWIO: WIO[b.State, Err, Out, Ctx] =
          WIO.Map(
            b.wio,
            identity[b.State],
            (s1: b.State, o1: Out1) => wio.mapValue(initState, o1),
          )
//          NewBehaviour[b.Error, Err, b.Value, Out, b.State](newWIO, b.state): NextWfState[Err, Out]
        NewBehaviour(newWIO, b.state, b.wakeupAt)
      },
      v => NewValue(v.value.map(x => wio.mapValue(initState, x))),
    )
  }

  protected def applyHandleError[ErrIn, TempOut <: WCState[Ctx]](
      wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut],
      wf: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn },
  ): NextWfState[Ctx, Err, Out] = {
    def newWf(err: ErrIn): NewBehaviour[Ctx, Err, Out] = {
      val (newState, newWio) = wio.handleError(err)
      NewBehaviour(newWio, Right(newState), None)
    }

    wf.fold(
      b => {
        b.state match {
          case Left(value) => newWf(value)
          case Right(v)    =>
            val adjustedHandler: ErrIn => (TempOut, WIO[TempOut, Err, Out, Ctx]) = err => {
              val (newState, newWio) = wio.handleError(err)
              (newState, newWio.transformInput[Any](_ => newState))
            }
            val newWIO: WIO[b.State, Err, Out, Ctx]                              = WIO.HandleError(b.wio, adjustedHandler, wio.handledErrorMeta, wio.newErrorMeta)
            NewBehaviour(newWIO, v.asRight, b.wakeupAt)
        }
      },
      v => {
        v.value match {
          case Left(value) => newWf(value)
          case Right(vv)   => NewValue(vv.asRight)
        }
      },
    )
  }

  protected def applyHandleErrorWith[ErrIn](
      wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err],
      wf: NextWfState[Ctx, ErrIn, Out] { type Error = ErrIn },
      currentState: WCState[Ctx],
  ): NextWfState[Ctx, Err, Out] = {
    def newWf(err: ErrIn): NewBehaviour[Ctx, Err, Out] = NewBehaviour(
      wio.handleError.provideInput((currentState, err)),
      Right(currentState),
      None,
    )

    wf.fold(
      b => {
        b.state match {
          case Left(value) => newWf(value)
          case Right(v)    =>
            val newWIO: WIO[b.State, Err, Out, Ctx] =
              WIO.HandleErrorWith(b.wio, wio.handleError, wio.handledErrorMeta, wio.newErrorCt)
            NewBehaviour(newWIO, v.asRight, b.wakeupAt)
        }
      },
      v => {
        v.value match {
          case Left(value) => newWf(value)
          case Right(vv)   => NewValue(vv.asRight)
        }
      },
    )
  }

  def applyLoop[LoopOut <: WCState[Ctx]](
      wio: WIO.Loop[Ctx, In, Err, LoopOut, Out],
      wf: NextWfState[Ctx, Err, LoopOut],
  ): NextWfState[Ctx, Err, Out] = {
    wf.fold[NextWfState[Ctx, Err, Out]](
      b => {
        val newWIO: WIO[b.State, Err, Out, Ctx] = wio.copy(current = b.wio)
        NewBehaviour(newWIO, b.state, b.wakeupAt): NextWfState[Ctx, Err, Out]
      },
      v =>
        v.value match {
          case Left(err)    => NewValue(Left(err))
          case Right(value) =>
            wio.stopCondition(value) match {
              case Some(newState) if !wio.isReturning => NewValue(Right(newState))
              case _                                  =>
                // if we the current exited we either finished main logic or return branch
                // if its main logic and return branch exists, we enter this, if not
                val newWIO =
                  if (wio.isReturning) wio.copy(current = wio.loop, isReturning = false)
                  else
                    wio.onRestart match {
                      case Some(onReturn) => wio.copy(current = onReturn, isReturning = true)
                      case None           => wio.copy(current = wio.loop, isReturning = false)
                    }
                NewBehaviour(newWIO, v.value, None)
            }
        },
    )
  }

  def selectMatching(wio: WIO.Fork[Ctx, In, Err, Out], in: In): Option[WIO[In, Err, Out, Ctx]] = {
    wio.branches.collectFirstSome(b => b.condition(in).map(interm => b.wio.transformInput[In](s => (s, interm))))
  }

  def convertResult[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], O1[_] <: WCState[Ctx]](
      wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, O1],
      newWf: NextWfState[InnerCtx, Err, InnerOut],
      input: In,
  ): NextWfState[Ctx, Err, Out] = {
    // we are interpretting WIO.Embedded and by definition its Out = MappingOutput[InnerOut]. Its just compiler forgetting it somehow
    def convert(x: NextWfState[Ctx, Err, O1[InnerOut]]): NextWfState[Ctx, Err, Out] = x.asInstanceOf
    newWf.fold(
      b => {
        val newEmbedding: WorkflowEmbedding.Aux[InnerCtx, Ctx, O1, Any]      = new WorkflowEmbedding[InnerCtx, Ctx, Any] {
          override def convertEvent(e: WCEvent[InnerCtx]): WCEvent[Ctx]           = wio.embedding.convertEvent(e)
          override def unconvertEvent(e: WCEvent[Ctx]): Option[WCEvent[InnerCtx]] = wio.embedding.unconvertEvent(e)
          override type OutputState[In <: WCState[InnerCtx]] = O1[In]
          override def convertState[In <: WCState[InnerCtx]](innerState: In, x: Any): OutputState[In] = wio.embedding.convertState(innerState, input)
          override def unconvertState(outerState: WCState[Ctx]): Option[WCState[InnerCtx]]            = wio.embedding.unconvertState(outerState)
        }
        val newEmbedded: WIO.Embedded[Ctx, Any, Err, InnerCtx, InnerOut, O1] =
          WIO.Embedded(b.wio.transformInput[Any](_ => b.state.toOption.get), newEmbedding, wio.initialState.compose(_ => input))
        val newBehaviour: NewBehaviour[Ctx, Err, O1[InnerOut]]               =
          NewBehaviour(newEmbedded, b.state.map(wio.embedding.convertState(_, input)), b.wakeupAt)
        convert(newBehaviour)
      },
      v => {
        val x: NewValue[Ctx, Err, O1[InnerOut]] = NewValue(v.value.map(wio.embedding.convertState(_, input)))
        convert(x)
      },
    )
  }

  def preserveHandleInterruption(
      interruption: WIO.Interruption[Ctx, Err, Out, ?, ?],
      newWf: NextWfState[Ctx, Err, Out],
  ): NextWfState[Ctx, Err, Out] = {
    newWf.fold(
      b => {
        val newBehaviour = WIO.HandleInterruption(b.wio, interruption)
        NewBehaviour(newBehaviour, b.state, b.wakeupAt)
      },
      v => v,
    )
  }

}

sealed trait NextWfState[C <: WorkflowContext, +E, +O <: WCState[C]] { self =>
  type Error

  def toActiveWorkflow(using E <:< Nothing): ActiveWorkflow[C] = this match {
    case behaviour: NextWfState.NewBehaviour[C, E, O] =>
      def cast[I](wio: workflows4s.wio.WIO[I, E, O, C])(using E <:< Nothing): workflows4s.wio.WIO[I, Nothing, O, C] = wio.asInstanceOf // TODO, cast
      ActiveWorkflow[C, behaviour.State](cast(behaviour.wio), behaviour.state.toOption.get, behaviour.wakeupAt)
    case value: NextWfState.NewValue[C, E, O]         => ActiveWorkflow(WIO.Noop(), value.value.toOption.get, None)
  }

  // its safe, compiler cant get the connection between
  @nowarn("msg=the type test for workflows4s.wio.NextWfState.NewBehaviour")
  def fold[T](mapBehaviour: NewBehaviour[C, E, O] { type Error = self.Error } => T, mapValue: NewValue[C, E, O] => T): T = this match {
    case behaviour: NewBehaviour[C, E, O] { type Error = self.Error } => mapBehaviour(behaviour)
    case value: NewValue[C, E, O]                                     => mapValue(value)
  }
}

object NextWfState {
  trait NewBehaviour[C <: WorkflowContext, +NextError, +NextValue <: WCState[C]] extends NextWfState[C, NextError, NextValue] {
    self =>
    type State <: WCState[C]
    type Error

    def wio: workflows4s.wio.WIO[State, NextError, NextValue, C]
    def state: Either[Error, State]
    def wakeupAt: Option[Instant]
  }

  object NewBehaviour {
    def apply[C <: WorkflowContext, E1, E2, O2 <: WCState[C], S1 <: WCState[C]](
        wio0: workflows4s.wio.WIO[S1, E2, O2, C],
        value0: Either[E1, S1],
        wakeupAt0: Option[Instant],
    ): NewBehaviour[C, E2, O2] = new NewBehaviour[C, E2, O2] {
      override type State = S1
      override type Error = E1
      override def wio: workflows4s.wio.WIO[State, E2, O2, C] = wio0
      override def state: Either[Error, State]                = value0
      def wakeupAt: Option[Instant]                           = wakeupAt0
    }
  }

  trait NewValue[C <: WorkflowContext, +E, +O <: WCState[C]] extends NextWfState[C, E, O] {
    def value: Either[E, O]
  }

  object NewValue {
    def apply[C <: WorkflowContext, E, O <: WCState[C], S](value0: Either[E, O]): NextWfState.NewValue[C, E, O] = new NewValue[C, E, O] {
      override def value: Either[E, O] = value0
    }
  }

}
