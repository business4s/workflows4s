package workflow4s.wio

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse

class Interpreter[C <: WorkflowContext](val journal: JournalPersistance[C#Event])

object Interpreter {

  sealed trait EventResponse[Ctx <: WorkflowContext]

  object EventResponse {
    case class Ok[Ctx <: WorkflowContext](newFlow: ActiveWorkflow.ForCtx[Ctx]) extends EventResponse[Ctx]

    case class UnexpectedEvent[Ctx <: WorkflowContext]() extends EventResponse[Ctx]
  }

  sealed trait ProceedResponse[Ctx <: WorkflowContext]

  object ProceedResponse {
    case class Executed[Ctx <: WorkflowContext](newFlow: IO[ActiveWorkflow.ForCtx[Ctx]]) extends ProceedResponse[Ctx]

    case class Noop[Ctx <: WorkflowContext]() extends ProceedResponse[Ctx]
  }

  sealed trait SignalResponse[Ctx <: WorkflowContext, Resp]

  object SignalResponse {
    case class Ok[Ctx <: WorkflowContext, Resp](value: IO[(ActiveWorkflow.ForCtx[Ctx], Resp)]) extends SignalResponse[Ctx, Resp]

    case class UnexpectedSignal[Ctx <: WorkflowContext, Resp]() extends SignalResponse[Ctx, Resp]
  }

  sealed trait QueryResponse[Resp]

  object QueryResponse {
    case class Ok[Resp](value: Resp) extends QueryResponse[Resp]

    case class UnexpectedQuery[Resp]() extends QueryResponse[Resp]
  }

}

trait VisitorModule[Ctx <: WorkflowContext] {
  val c: Ctx

  import c.WIO
  import NextWfState.{NewBehaviour, NewValue}
  type WIOC                 = WIO.type
  type WIO[-In, +Err, +Out] = Ctx#WIO[In, Err, Out]

  abstract class Visitor[In, Err, Out](wio: Ctx#WIO[In, Err, Out]) {
    type Result

    def onSignal[Sig, Evt, Resp](wio: WIOC#HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result
    def onRunIO[Evt](wio: WIOC#RunIO[In, Err, Out, Evt]): Result
    def onFlatMap[Out1, Err1 <: Err](wio: WIOC#FlatMap[Err1, Err, Out1, Out, In]): Result
    def onMap[In1, Out1](wio: WIOC#Map[In1, Err, Out1, In, Out]): Result
    def onHandleQuery[Qr, QrState, Resp](wio: WIOC#HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result
    def onNoop(wio: WIOC#Noop): Result
    def onNamed(wio: WIOC#Named[In, Err, Out]): Result
    def onHandleError[ErrIn](wio: WIOC#HandleError[In, Err, Out, ErrIn]): Result
    def onHandleErrorWith[ErrIn](wio: WIOC#HandleErrorWith[In, ErrIn, Out, Err]): Result
    def onAndThen[Out1](wio: WIOC#AndThen[In, Err, Out1, Out]): Result
    def onPure(wio: WIOC#Pure[In, Err, Out]): Result
    def onDoWhile[Out1](wio: WIOC#DoWhile[In, Err, Out1, Out]): Result
    def onFork(wio: WIOC#Fork[In, Err, Out]): Result

    def run: Result = {
      wio match {
        case x: WIOC#HandleSignal[?, ?, ?, ?, ?, ?]     => onSignal(x)
        case x: WIOC#HandleQuery[?, ?, ?, ?, ?, ?]      => onHandleQuery(x)
        case x: WIOC#RunIO[?, ?, ?, ?]                  => onRunIO(x)
        // https://github.com/scala/scala3/issues/20040
        case x: WIOC#FlatMap[? <: Err, Err, ?, Out, In] =>
          x match {
            case x: WIOC#FlatMap[err1, Err, out1, Out, In] => onFlatMap[out1, err1](x)
          }
        case x: WIOC#Map[?, Err, ?, In, Out]            => onMap(x)
        case x: WIOC#Noop                               => onNoop(x)
        case x: WIOC#HandleError[?, ?, ?, ?]            => onHandleError(x)
        case x: WIOC#Named[?, ?, ?]                     => onNamed(x)
        case x: WIOC#AndThen[?, ?, ?, ?]                => onAndThen(x)
        case x: WIOC#Pure[?, ?, ?]                      => onPure(x)
        case x: WIOC#HandleErrorWith[?, ?, ?, ?]        => onHandleErrorWith(x)
        case x: WIOC#DoWhile[?, ?, ?, ?]                => onDoWhile(x)
        case x: WIOC#Fork[?, ?, ?]                      => onFork(x)
      }
    }

    protected def preserveFlatMap[Out1, Err1 <: Err](
        wio: WIOC#FlatMap[Err1, Err, Out1, Out, In],
        wf: NextWfState[Err1, Out1],
    ): NextWfState[Err, Out] = {
      wf.fold(
        b => {
          b.state match {
            case Left(err)    =>
              // TODO this should be safe but we somehow lost information that state produces from evaluating Flatmap.base has Error = Err1
              val errCasted = err.asInstanceOf[Err]
              NewValue(errCasted.asLeft)
            case Right(value) =>
              val effectiveWIO: c.WIO.FlatMap[Err1, Err, Out1, Out, b.State] = c.WIO.FlatMap(b.wio, (x: Out1) => wio.getNext(x), wio.errorCt)
              NewBehaviour(effectiveWIO, b.state)
          }
        },
        v => {
          v.value match {
            case Left(err)     => NewValue(Left(err))
            case Right(output) => NewBehaviour(wio.getNext(output), Right(output))
          }
        },
      )
    }

    protected def preserveAndThen[Out1](
        wio: WIOC#AndThen[In, Err, Out1, Out],
        wf: NextWfState[Err, Out1],
    ): NextWfState[Err, Out] =
      wf.fold(
        b => {
          val newWIO = WIO.AndThen(b.wio, wio.second)
          NewBehaviour(newWIO, b.state)
        },
        v => {
          NewBehaviour(wio.second, v.value) // we ignore error, might backfire
        },
      )

    def preserveMap[Out1, In1](
        wio: WIOC#Map[In1, Err, Out1, In, Out],
        wf: NextWfState[Err, Out1],
        initState: In,
    ): NextWfState[Err, Out] = {
      wf.fold[NextWfState[Err, Out]](
        b => {
          val newWIO: c.WIO[b.State, Err, Out] =
            WIO.Map(
              b.wio,
              identity[b.State],
              (s1: b.State, o1: Out1) => wio.mapValue(initState, o1),
            )
//          NewBehaviour[b.Error, Err, b.Value, Out, b.State](newWIO, b.state): NextWfState[Err, Out]
          NewBehaviour(newWIO, b.state)
        },
        v => NewValue(v.value.map(x => wio.mapValue(initState, x))),
      )
    }

    protected def preserveHandleQuery[Qr, QrSt, Resp](
        wio: WIOC#HandleQuery[In, Err, Out, Qr, QrSt, Resp],
        wf: NextWfState[Err, Out],
    ): NextWfState[Err, Out] = {
      wf.fold(
        b => {
          val newWio = WIO.HandleQuery(wio.queryHandler, b.wio)
          NewBehaviour(newWio, b.state)
        },
        v => v, // if its direct, we leave the query
      )
    }

    protected def applyHandleError[ErrIn](
        wio: WIOC#HandleError[In, Err, Out, ErrIn],
        wf: NextWfState[ErrIn, Out] { type Error = ErrIn },
        originalState: In,
    ): NextWfState[Err, Out] = {
      def newWf(err: ErrIn) = NewBehaviour(wio.handleError(err), Right(originalState))

      wf.fold(
        b => {
          b.state match {
            case Left(value) => newWf(value)
            case Right(v)    =>
              val adjustedHandler: ErrIn => c.WIO[b.State, Err, Out] = err => wio.handleError(err).transformInput[Any](x => originalState)
              val newWIO: c.WIO[b.State, Err, Out]                   = WIO.HandleError(b.wio, adjustedHandler, wio.handledErrorMeta, wio.newErrorMeta)
              NewBehaviour(newWIO, v.asRight)
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
        wio: WIOC#HandleErrorWith[In, ErrIn, Out, Err],
        wf: NextWfState[ErrIn, Out] { type Error = ErrIn },
        originalState: In,
    ): NextWfState[Err, Out] = {
      def newWf(err: ErrIn) = NewBehaviour(
        wio.handleError.transformInput[Any](_ => (originalState, err)),
        Right(originalState -> ()),
      )

      wf.fold(
        b => {
          b.state match {
            case Left(value) => newWf(value)
            case Right(v)    =>
              val adjustedHandler                  = wio.handleError.transformInput[(Any, ErrIn)](x => (originalState, x._2))
              val newWIO: c.WIO[b.State, Err, Out] = WIO.HandleErrorWith(b.wio, adjustedHandler, wio.handledErrorMeta, wio.newErrorCt)
              NewBehaviour(newWIO, v.asRight)
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

    def applyOnDoWhile[LoopOut](
        wio: WIOC#DoWhile[In, Err, LoopOut, Out],
        wf: NextWfState[Err, LoopOut],
    ): NextWfState[Err, Out] = {
      wf.fold[NextWfState[Err, Out]](
        b => {
          val newWIO: c.WIO[b.State, Err, Out] = WIO.DoWhile(wio.loop, wio.stopCondition, b.wio)
          NewBehaviour(newWIO, b.state): NextWfState[Err, Out]
        },
        v =>
          v.value match {
            case Left(err)    => NewValue(Left(err))
            case Right(value) =>
              wio.stopCondition(value) match {
                case Some(newState) => NewValue(Right(newState))
                case None           =>
                  val newWIO = WIO.DoWhile(wio.loop, wio.stopCondition, wio.loop)
                  NewBehaviour(newWIO, v.value)
              }
          },
      )
    }

    def selectMatching(wio: WIO.Fork[In, Err, Out], in: In): Option[c.WIO[In, Err, Out]] = {
      wio.branches.collectFirstSome(b => b.condition(in).map(interm => b.wio.transformInput[In](s => (s, interm))))
    }

  }

  sealed trait NextWfState[+E, +O] { self =>
    type Error

    def toActiveWorkflow(interpreter: Interpreter[Ctx])(using E <:< Nothing): ActiveWorkflow.ForCtx[Ctx] = this match {
      case behaviour: NextWfState.NewBehaviour[E, O] =>
        def cast[I,O](wio: WIO[I, E, O])(using E <:< Nothing): WIO[I, Nothing, O] = wio.asInstanceOf // TODO, cast
        ActiveWorkflow[Ctx, behaviour.State, Any](cast(behaviour.wio), behaviour.state.toOption.get)(interpreter)
      case value: NextWfState.NewValue[E, O]         => ActiveWorkflow(WIO.Noop(), value.value)(interpreter)
    }

    def fold[T](mapBehaviour: NewBehaviour[E, O] { type Error = self.Error } => T, mapValue: NewValue[E, O] => T): T = this match {
      case behaviour: NewBehaviour[E, O] { type Error = self.Error } => mapBehaviour(behaviour)
      case value: NewValue[E, O]                                     => mapValue(value)
    }
  }

  object NextWfState {
    trait NewBehaviour[+NextError, +NextValue] extends NextWfState[NextError, NextValue] {
      self =>
      type State
      type Error

      def wio: c.WIO[State, NextError, NextValue]
      def state: Either[Error, State]
    }

    object NewBehaviour {
      def apply[E1, E2, O2, S1](
          wio0: c.WIO[S1, E2, O2],
          value0: Either[E1, S1],
      ): NewBehaviour[E2, O2] = new NewBehaviour[E2, O2] {
        override type State = S1
        override type Error = E1
        override def wio: c.WIO[State, E2, O2]   = wio0
        override def state: Either[Error, State] = value0
      }
    }

    trait NewValue[+E, +O] extends NextWfState[E, O] {
      def value: Either[E, O]
    }

    object NewValue {
      def apply[E, O, S](value0: Either[E, O]): NextWfState.NewValue[E, O] = new NewValue[E, O] {
        override def value: Either[E, O] = value0
      }
    }

  }

}
