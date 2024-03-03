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

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp], state: StIn): DirectOut
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err], state: StIn): DirectOut
    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut], state: StIn): FlatMapOut
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], state: StIn): DispatchResult
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult
    def onNoop(wio: WIO.Noop): DirectOut

    def onError(error: Err): DirectOut = ???

    def run(errOrState: Either[Err, StIn]): DispatchResult = {
      errOrState match {
        case Left(err)    => onError(err).asLeft
        case Right(state) =>
          wio match {
            case x @ HandleSignal(_, _, _, _)             => onSignal(x, state).asLeft
            case x @ WIO.HandleQuery(queryHandler, inner) => onHandleQuery(x, state)
            case x @ WIO.RunIO(buildIO, evtHandler)       => onRunIO(x, state).asLeft
            case x @ WIO.FlatMap(base, getNext)           => onFlatMap(x, state).asRight.asInstanceOf[DispatchResult] // TODO
            case x @ WIO.Map(base, f)                     => onMap(x, state)
            case x @ WIO.Noop()                           => onNoop(x).asLeft
          }
      }
    }
  }

}
