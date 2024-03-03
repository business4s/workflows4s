package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.WIO.Total
import workflow4s.wio.internal.{CurrentStateEvaluator, EventEvaluator, ProceedEvaluator, QueryEvaluator, SignalEvaluator}

abstract class ActiveWorkflow(val interpreter: Interpreter) {
  type State
  type Output
  type Error
  val value: Either[Error, (State, Output)]
  def wio: WIO.Total[State]

  private def errOrState = value.map(_._1)

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Resp] =
    SignalEvaluator.handleSignal(signalDef, req, wio, errOrState, interpreter)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]   =
    QueryEvaluator.handleQuery(signalDef, req, wio, errOrState)
  def handleEvent(event: Any): EventResponse                                                   =
    EventEvaluator.handleEvent(event, wio, errOrState, interpreter)
  def proceed: ProceedResponse                                                                 =
    ProceedEvaluator.proceed(wio, errOrState, interpreter)

  def getDesc = CurrentStateEvaluator.getCurrentStateDescription(wio)

}

object ActiveWorkflow {

  def apply[St, O, E](wio0: WIO.Total[St], interpreter: Interpreter, value0: Either[E, (St, O)]) = new ActiveWorkflow(interpreter) {
    override type State = St
    override def wio: Total[St] = wio0
    override type Output = O
    override type Error  = E
    override val value: Either[Error, (State, Output)] = value0
  }
}

trait WfAndState {
  type Err
  type StIn
  type StOut
  type NextValue
  def wio: WIO[Err, NextValue, StIn, StOut]
  type Value
  val value: Either[Err, (StIn, Value)]
}

object WfAndState {

  type T[E, O1, SOut] = WfAndState { type Err = E; type StOut = SOut; type NextValue = O1 }

  def apply[E, O1, O2, S1, S2](wio0: WIO[E, O2, S1, S2], value0: Either[E, (S1, O1)]) = new WfAndState {
    override type Err       = E
    override type StIn      = S1
    override type StOut     = S2
    override type NextValue = O2
    override def wio: WIO[Err, NextValue, StIn, StOut] = wio0
    override type Value = O1
    override val value: Either[Err, (StIn, Value)] = value0
  }
}
