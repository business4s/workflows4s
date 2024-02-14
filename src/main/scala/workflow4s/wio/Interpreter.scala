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

  abstract class Visitor {
    type DirectOut[StOut, O]
    type FlatMapOut[Err, Out, SOut]

    type DispatchResult[Err, Out, SOut] = Either[DirectOut[SOut, Out], FlatMapOut[Err, Out, SOut]]

    def onSignal[Sig, StIn, StOut, Evt, O](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): DirectOut[StOut, O]
    def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): DirectOut[StOut, O]
    def onFlatMap[Err, Out1, Out2, StIn, StOut, StOut2](
        wio: WIO.FlatMap[Err, Out1, Out2, StIn, StOut, StOut2],
        state: StIn,
    ): FlatMapOut[Err, Out1, StOut2]
    def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): DispatchResult[Err, Out2, StOut]
    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult[Err, Out, StOut]
    def onNoop[St, O](wio: WIO.Noop): DirectOut[St, O]

    def dispatch[Err, O, StateIn, StateOut](
        wio: WIO[Err, O, StateIn, StateOut],
        state: StateIn,
    ): DispatchResult[Err, O, StateOut] = {
      wio match {
        case x @ HandleSignal(sigHandler, evtHandler) => onSignal(x, state).asLeft
        case x @ WIO.HandleQuery(queryHandler, inner) => onHandleQuery(x, state)
        case x @ WIO.RunIO(buildIO, evtHandler)       => onRunIO(x, state).asLeft
        case x @ WIO.FlatMap(base, getNext)           => onFlatMap(x, state).asRight.asInstanceOf[DispatchResult[Err, O, StateOut]] // TODO
        case x @ WIO.Map(base, f)                     => onMap(x, state)
        case x @ WIO.Noop()                           => onNoop(x).asLeft
      }
    }
  }

}
