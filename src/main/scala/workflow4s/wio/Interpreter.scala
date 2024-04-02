package workflow4s.wio

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse

class Interpreter(val journal: JournalPersistance)

object Interpreter {

  sealed trait EventResponse

  object EventResponse {
    case class Ok(newFlow: ActiveWorkflow) extends EventResponse

    case class UnexpectedEvent() extends EventResponse
  }

  sealed trait ProceedResponse

  object ProceedResponse {
    case class Executed(newFlow: IO[ActiveWorkflow]) extends ProceedResponse

    case class Noop() extends ProceedResponse
  }

  sealed trait SignalResponse[Resp]

  object SignalResponse {
    case class Ok[Resp](value: IO[(ActiveWorkflow, Resp)]) extends SignalResponse[Resp]

    case class UnexpectedSignal[Resp]() extends SignalResponse[Resp]
  }

  sealed trait QueryResponse[Resp]

  object QueryResponse {
    case class Ok[Resp](value: Resp) extends QueryResponse[Resp]

    case class UnexpectedQuery[Resp]() extends QueryResponse[Resp]
  }

}

trait VisitorModule {
  val c: WorkflowContext

  import c.WIO
  import NextWfState.{NewBehaviour, NewValue}

  abstract class Visitor[In, Err, Out](wio: c.WIO[In, Err, Out]) {
    type Result

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result
    def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result
    def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result
    def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result
    def onNoop(wio: WIO.Noop): Result
    def onNamed(wio: WIO.Named[In, Err, Out]): Result
    def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result
    def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result
    def onPure(wio: WIO.Pure[In, Err, Out]): Result
    def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result
    def onFork(wio: WIO.Fork[In, Err, Out]): Result

    def run: Result = {
      wio match {
        case x @ WIO.HandleSignal(_, _, _, _, _)       => onSignal(x)
        case x @ WIO.HandleQuery(_, _)                 => onHandleQuery(x)
        case x @ WIO.RunIO(_, _, _)                    => onRunIO(x)
        // https://github.com/scala/scala3/issues/20040
        case x: WIO.FlatMap[? <: Err, Err, ?, Out, In] =>
          x match {
            case x: WIO.FlatMap[err1, Err, out1, Out, In] => onFlatMap[out1, err1](x)
          }
        case x: WIO.Map[?, Err, ?, In, Out]            => onMap(x)
        case x @ WIO.Noop()                            => onNoop(x)
        case x @ WIO.HandleError(_, _, _, _)           => onHandleError(x)
        case x @ WIO.Named(_, _, _, _)                 => onNamed(x)
        case x @ WIO.AndThen(_, _)                     => onAndThen(x)
        case x @ WIO.Pure(_, _)                        => onPure(x)
        case x @ WIO.HandleErrorWith(_, _, _, _)       => onHandleErrorWith(x)
        case x: WIO.DoWhile[In, Err, stOut1, Out]      => onDoWhile(x)
        case x @ WIO.Fork(_)                           => onFork(x)
      }
    }

    protected def preserveFlatMap[Out1, Err1 <: Err](
        wio: WIO.FlatMap[Err1, Err, Out1, Out, In],
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
              val effectiveWIO: c.WIO.FlatMap[Err1, Err, Out1, Out, b.State] = WIO.FlatMap(b.wio, (x: Out1) => wio.getNext(x), wio.errorCt)
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
        wio: WIO.AndThen[In, Err, Out1, Out],
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
        wio: WIO.Map[In1, Err, Out1, In, Out],
        wf: NextWfState[Err, Out1],
        initState: In,
    ): NextWfState[Err, Out] = {
      wf.fold[NextWfState[Err, Out]](
        b => {
          val newWIO: WIO[b.State, Err, Out] =
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
        wio: WIO.HandleQuery[In, Err, Out, Qr, QrSt, Resp],
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
        wio: WIO.HandleError[In, Err, Out, ErrIn],
        wf: NextWfState[ErrIn, Out] { type Error = ErrIn },
        originalState: In,
    ): NextWfState[Err, Out] = {
      def newWf(err: ErrIn) = NewBehaviour(wio.handleError(err), Right(originalState))

      wf.fold(
        b => {
          b.state match {
            case Left(value) => newWf(value)
            case Right(v)    =>
              val adjustedHandler: ErrIn => WIO[b.State, Err, Out] = err => wio.handleError(err).transformInput[Any](x => originalState)
              val newWIO: WIO[b.State, Err, Out]                   = WIO.HandleError(b.wio, adjustedHandler, wio.handledErrorMeta, wio.newErrorMeta)
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
        wio: WIO.HandleErrorWith[In, ErrIn, Out, Err],
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
              val adjustedHandler                = wio.handleError.transformInput[(Any, ErrIn)](x => (originalState, x._2))
              val newWIO: WIO[b.State, Err, Out] = WIO.HandleErrorWith(b.wio, adjustedHandler, wio.handledErrorMeta, wio.newErrorCt)
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
        wio: WIO.DoWhile[In, Err, LoopOut, Out],
        wf: NextWfState[Err, LoopOut],
    ): NextWfState[Err, Out] = {
      wf.fold[NextWfState[Err, Out]](
        b => {
          val newWIO: WIO[b.State, Err, Out] = WIO.DoWhile(wio.loop, wio.stopCondition, b.wio)
          NewBehaviour(newWIO, b.state): NextWfState[Err, Out]
        },
        v =>
          v.value match {
            case Left(err)             => NewValue(Left(err))
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

    def selectMatching(wio: WIO.Fork[In, Err, Out], in: In): Option[WIO[In, Err, Out]] = {
      wio.branches.collectFirstSome(b => b.condition(in).map(interm => b.wio.transformInput[In](s => (s, interm))))
    }

  }

  sealed trait NextWfState[+E, +O] { self =>
    type Error

    def toActiveWorkflow(interpreter: Interpreter): ActiveWorkflow = this match {
      case behaviour: NextWfState.NewBehaviour[E, O] => ActiveWorkflow(behaviour.wio, interpreter, behaviour.state)
      case value: NextWfState.NewValue[E, O]         => ActiveWorkflow(WIO.Noop(), interpreter, value.value)
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

      def wio: WIO[State, NextError, NextValue]
      def state: Either[Error, State]
    }

    object NewBehaviour {
      def apply[E1, E2, O2, S1](
          wio0: WIO[S1, E2, O2],
          value0: Either[E1, S1],
      ): NewBehaviour[E2, O2] = new NewBehaviour[E2, O2] {
        override type State = S1
        override type Error = E1
        override def wio: WIO[State, E2, O2]     = wio0
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
