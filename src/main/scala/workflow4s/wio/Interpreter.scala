package workflow4s.wio

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.ProceedResponse
import workflow4s.wio.WIO.HandleSignal

class Interpreter(val journal: JournalPersistance)

object Interpreter {

  sealed trait EventResponse
  object EventResponse {
    case class Ok(newFlow: ActiveWorkflow) extends EventResponse
    case class UnexpectedEvent()           extends EventResponse
  }

  sealed trait ProceedResponse
  object ProceedResponse {
    case class Executed(newFlow: IO[ActiveWorkflow]) extends ProceedResponse
    case class Noop()                                extends ProceedResponse
  }

  sealed trait SignalResponse[Resp]
  object SignalResponse {
    case class Ok[Resp](value: IO[(ActiveWorkflow, Resp)]) extends SignalResponse[Resp]
    case class UnexpectedSignal[Resp]()                    extends SignalResponse[Resp]
  }

  sealed trait QueryResponse[Resp]
  object QueryResponse {
    case class Ok[Resp](value: Resp)   extends QueryResponse[Resp]
    case class UnexpectedQuery[Resp]() extends QueryResponse[Resp]
  }

  abstract class Visitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]) {
    type DirectOut
    type FlatMapOut
    type DispatchResult = Either[DirectOut, FlatMapOut]

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DirectOut
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DirectOut
    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult
    def onNoop(wio: WIO.Noop): DirectOut
    def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult
    def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult
    def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut
    def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DirectOut

    def run: DispatchResult = {
      wio match {
        case x @ HandleSignal(_, _, _, _) => onSignal(x).asLeft
        case x @ WIO.HandleQuery(_, _)    => onHandleQuery(x)
        case x @ WIO.RunIO(_, _)          => onRunIO(x).asLeft
        case x @ WIO.FlatMap(_, _)        => onFlatMap(x).asRight
        case x @ WIO.Map(_, _)            => onMap(x)
        case x @ WIO.Noop()               => onNoop(x).asLeft
        case x @ WIO.HandleError(_, _)    => onHandleError(x)
        case x @ WIO.Named(_, _, _)       => onNamed(x)
        case x @ WIO.AndThen(_, _)        => onAndThen(x).asRight
        case x @ WIO.Pure(_)              => onPure(x).asLeft
      }
    }
  }

}
