package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.WIO.Total
import workflow4s.wio.internal.{CurrentStateEvaluator, EventEvaluator, ProceedEvaluator, QueryEvaluator, SignalEvaluator}

abstract class ActiveWorkflow(val interpreter: Interpreter) {
  type State
  def state: State
  def wio: WIO.Total[State]
  type Value
  val value: Value

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Resp] =
    SignalEvaluator.handleSignal(signalDef, req, wio, state, interpreter)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]   =
    QueryEvaluator.handleQuery(signalDef, req, wio, state)
  def handleEvent(event: Any): EventResponse                                                   =
    EventEvaluator.handleEvent(event, wio, state, interpreter)
  def proceed: ProceedResponse                                                            =
    ProceedEvaluator.proceed[State, Any](wio, state, interpreter)

  def getDesc = CurrentStateEvaluator.getCurrentStateDescription(wio, state)

}

object ActiveWorkflow {

//  type T[St, O] = ActiveWorkflow[O] { type State = St }
  type V[V0] = ActiveWorkflow { type Value = V0 }

  def apply[St, O](state0: St, wio0: WIO.Total[St], interpreter: Interpreter, value0: O) = new ActiveWorkflow(interpreter) {
    override type State = St
    override val state: St      = state0
    override def wio: Total[St] = wio0
    override type Value = O
    override val value: O = value0
  }
}

trait WfAndState {
  type Err
  type StIn
  type StOut
  type NextValue
  def state: StIn
  def wio: WIO[Err, NextValue, StIn, StOut]
  type Value
  val value: Value
}

object WfAndState {

  type T[E, O1, SOut] = WfAndState { type Err = E; type StOut = SOut; type Value = O1 }

  def apply[E, O1, O2, S1, S2](state0: S1, wio0: WIO[E, O2, S1, S2], value0: O1) = new WfAndState {
    override type Err       = E
    override type StIn      = S1
    override type StOut     = S2
    override type NextValue = O2
    override def state: StIn                           = state0
    override def wio: WIO[Err, NextValue, StIn, StOut] = wio0
    override type Value = O1
    override val value: Value = value0
  }
}
