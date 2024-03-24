package workflow4s.wio

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio.NextWfState.{NewBehaviour, NewValue}
import workflow4s.wio.WIO.HandleSignal

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

  abstract class Visitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]) {
    type DispatchResult

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult

    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult

    def onNoop(wio: WIO.Noop): DispatchResult

    def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult

    def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult

    def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](
        wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out],
    ): DispatchResult

    def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult

    def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult
    def onDoWhile[StOut1](wio: WIO.DoWhile[Err, Out, StIn, StOut1, StOut]): DispatchResult
    def onFork(wio: WIO.Fork[Err, Out, StIn, StOut]): DispatchResult

    def run: DispatchResult = {
      wio match {
        case x @ HandleSignal(_, _, _, _, _)     => onSignal(x)
        case x @ WIO.HandleQuery(_, _)           => onHandleQuery(x)
        case x @ WIO.RunIO(_, _, _)              => onRunIO(x)
        case x @ WIO.FlatMap(_, _, _)            => onFlatMap(x)
        case x @ WIO.Map(_, _, _)                => onMap(x)
        case x @ WIO.Noop()                      => onNoop(x)
        case x @ WIO.HandleError(_, _, _, _)     => onHandleError(x)
        case x @ WIO.Named(_, _, _, _)           => onNamed(x)
        case x @ WIO.AndThen(_, _)               => onAndThen(x)
        case x @ WIO.Pure(_, _)                  => onPure(x)
        case x @ WIO.HandleErrorWith(_, _, _, _) => onHandleErrorWith(x)
        case x @ WIO.DoWhile(_, _, _)            => onDoWhile(x)
        case x @ WIO.Fork(_)                     => onFork(x)
      }
    }

    protected def preserveFlatMap[Out1, StOut1, Err1 <: Err](
        wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut],
        wf: NextWfState[Err1, Out1, StOut1],
    ): NextWfState[Err, Out, StOut] = {
      wf.fold(
        b => {
          b.value match {
            case Left(err)    =>
              // TODO this should be safe but we somehow lost information that state produces from evaluating Flatmap.base has Error = Err1
              val errCasted = err.asInstanceOf[Err]
              NewValue(errCasted.asLeft)
            case Right(value) =>
              val effectiveWIO = WIO.FlatMap(b.wio, (x: Out1) => wio.getNext(x), wio.errorCt)
              NewBehaviour(effectiveWIO, b.value)
          }
        },
        v => {
          v.value match {
            case Left(err)         => NewValue(Left(err))
            case Right((st, vall)) => NewBehaviour(wio.getNext(vall), Right((st, vall)))
          }
        },
      )
    }

    protected def preserveAndThen[Out1, StOut1](
        wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut],
        wf: NextWfState[Err, Out1, StOut1],
    ): NextWfState[Err, Out, StOut] =
      wf.fold(
        b => {
          val newWIO = WIO.AndThen(b.wio, wio.second)
          NewBehaviour(newWIO, b.value)
        },
        v => {
          NewBehaviour(wio.second, v.value) // we ignore error, might backfire
        },
      )

    def preserveMap[Out1, StIn1, StOut1](
        wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut],
        wf: NextWfState[Err, Out1, StOut1],
        initState: StIn,
    ): NextWfState[Err, Out, StOut] = {
      wf.fold[NextWfState[Err, Out, StOut]](
        b => {
          val newWIO: WIO[Err, Out, b.State, StOut] =
            WIO.Map(
              b.wio,
              identity[b.State],
              (s1: b.State, s2: StOut1, o1: Out1) => wio.mapValue(initState, s2, o1),
            )
          NewBehaviour[b.Error, Err, b.Value, Out, b.State, StOut](newWIO, b.value): NextWfState[Err, Out, StOut]
        },
        v => NewValue(v.value.map(x => wio.mapValue(initState, x._1, x._2))),
      )
    }

    protected def preserveHandleQuery[Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        wf: NextWfState[Err, Out, StOut],
    ): NextWfState[Err, Out, StOut] = {
      wf.fold(
        b => {
          val newWio = WIO.HandleQuery(wio.queryHandler, b.wio)
          NewBehaviour(newWio, b.value)
        },
        v => v, // if its direct, we leave the query
      )
    }

    protected def applyHandleError[ErrIn](
        wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn],
        wf: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn },
        originalState: StIn,
    ): NextWfState[Err, Out, StOut] = {
      def newWf(err: ErrIn) = NewBehaviour[ErrIn, Err, Any, Out, StIn, StOut](wio.handleError(err), Right(originalState -> ()))
      wf.fold(
        b => {
          b.value match {
            case Left(value) => newWf(value)
            case Right(v)    =>
              val adjustedHandler: ErrIn => WIO[Err, Out, b.State, StOut] = err => wio.handleError(err).transformInputState[Any](x => originalState)
              val newWIO: WIO[Err, Out, b.State, StOut]                   = WIO.HandleError(b.wio, adjustedHandler, wio.handledErrorCt, wio.newErrorCt)
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

    protected def applyHandleErrorWith[ErrIn, HandlerStIn >: StIn, BaseOut >: Out](
        wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStIn, Out],
        wf: NextWfState[ErrIn, Out, StOut] { type Error = ErrIn },
        originalState: StIn,
    ): NextWfState[Err, Out, StOut] = {
      def newWf(err: ErrIn) = NewBehaviour[ErrIn, Err, Any, Out, StIn, StOut](
        wio.handleError.transformInputState[Any](_ => (originalState, err)),
        Right(originalState -> ()),
      )
      wf.fold(
        b => {
          b.value match {
            case Left(value) => newWf(value)
            case Right(v)    =>
              val adjustedHandler                       = wio.handleError.transformInputState[(Any, ErrIn)](x => (originalState, x._2))
              val newWIO: WIO[Err, Out, b.State, StOut] = WIO.HandleErrorWith(b.wio, adjustedHandler, wio.handledErrorCt, wio.newErrorCt)
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

    def applyOnDoWhile[StOut1](
        wio: WIO.DoWhile[Err, Out, StIn, StOut1, StOut],
        wf: NextWfState[Err, Out, StOut1],
    ): NextWfState[Err, Out, StOut] = {
      wf.fold[NextWfState[Err, Out, StOut]](
        b => {
          val newWIO: WIO[Err, Out, b.State, StOut] = WIO.DoWhile(wio.loop, wio.stopCondition, b.wio)
          NewBehaviour[b.Error, Err, b.Value, Out, b.State, StOut](newWIO, b.value): NextWfState[Err, Out, StOut]
        },
        v =>
          v.value match {
            case Left(err)             => NewValue(Left(err))
            case Right((state, value)) =>
              wio.stopCondition(state) match {
                case Some(newState) => NewValue(Right((newState, value)))
                case None           =>
                  val newWIO = WIO.DoWhile(wio.loop, wio.stopCondition, wio.loop)
                  NewBehaviour(newWIO, v.value)
              }
          },
      )
    }

    def selectMatching(wio: WIO.Fork[Err, Out, StIn, StOut], in: StIn): Option[WIO[Err, Out, StIn, StOut]] = {
      wio.branches.collectFirstSome(b => b.condition(in).map(interm => b.wio.transformInputState[StIn](s => (s, interm))))
    }

  }
}
