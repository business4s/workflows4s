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

    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult

    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult

    def onNoop(wio: WIO.Noop): DispatchResult

    def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult

    def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult

    def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult

    def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DispatchResult

    def run: DispatchResult = {
      wio match {
        case x @ HandleSignal(_, _, _, _) => onSignal(x)
        case x @ WIO.HandleQuery(_, _)    => onHandleQuery(x)
        case x @ WIO.RunIO(_, _)          => onRunIO(x)
        case x @ WIO.FlatMap(_, _)        => onFlatMap(x)
        case x @ WIO.Map(_, _)            => onMap(x)
        case x @ WIO.Noop()               => onNoop(x)
        case x @ WIO.HandleError(_, _)    => onHandleError(x)
        case x @ WIO.Named(_, _, _)       => onNamed(x)
        case x @ WIO.AndThen(_, _)        => onAndThen(x)
        case x @ WIO.Pure(_)              => onPure(x)
      }
    }

    protected def preserveFlatMap[Out1, StOut1, Err1 <: Err](
        wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut],
        wf: NextWfState[Err1, Out1, StOut1],
    ): NextWfState[Err, Out, StOut] = {
      wf.fold(
        b => {
          val effectiveWIO = WIO.FlatMap(
            b.wio,
            (x: Out1) =>
              b.value
                .map({ case (_, value) => wio.getNext(x) })
                .leftMap(err => WIO.raise[StOut1](err))
                .merge,
          )
          NewBehaviour(effectiveWIO, b.value)
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

    protected def preserveMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], wf: NextWfState[Err, Out1, StOut]): NextWfState[Err, Out, StOut] = {
      wf.fold(
        b => {
          val newWIO = WIO.Map(b.wio, (x: Out1) => wio.mapValue(x))
          NewBehaviour(newWIO, b.value)
        },
        v => NewValue(v.value.map(_.map(wio.mapValue))),
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

    protected def applyHandleError[ErrIn <: Err](
        wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn],
        wf: NextWfState[ErrIn, Out, StOut],
    ): NextWfState[Err, Out, StOut] = {
      def newWf(err: ErrIn) = NewBehaviour[ErrIn, Err, Out, Out, StIn, StOut](wio.handleError(err), Left(err))
      wf.fold(
        b => {
          b.value match {
            case Left(value) => newWf(value)
            case Right(_)    => b.widenErr[Err]
          }
        },
        v => {
          v.value match {
            case Left(value) => newWf(value)
            case Right(_)    => v
          }
        },
      )
    }

  }
}
