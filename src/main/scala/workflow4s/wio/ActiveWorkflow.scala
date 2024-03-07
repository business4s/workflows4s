package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.NextWfState.{NewBehaviour, NewValue}
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

sealed trait NextWfState[+E, +O, +S] {

  def toActiveWorkflow(interpreter: Interpreter): ActiveWorkflow = this match {
    case behaviour: NextWfState.NewBehaviour[E, O, S] => ActiveWorkflow(behaviour.wio, interpreter, behaviour.value)
    case value: NextWfState.NewValue[E, O, S]         => ActiveWorkflow(WIO.Noop(), interpreter, value.value)
  }

  def fold[T](mapBehaviour: NewBehaviour[E, O, S] => T, mapValue: NewValue[E, O, S] => T): T = this match {
    case behaviour: NewBehaviour[E, O, S] => mapBehaviour(behaviour)
    case value: NewValue[E, O, S]         => mapValue(value)
  }
}
object NextWfState {
  trait NewBehaviour[+NextError, +NextValue, +NextState] extends NextWfState[NextError, NextValue, NextState] { self =>
    type State
    type Error <: NextError
    type Value

    def wio: WIO[NextError, NextValue, State, NextState]
    def value: Either[Error, (State, Value)]

    def widenErr[E2 >: NextError]: NewBehaviour[E2, NextValue, NextState] = new NewBehaviour[E2, NextValue, NextState] {
      type State = self.State
      type Error = self.Error
      type Value = self.Value
      def wio: WIO[NextError, NextValue, State, NextState] = self.wio
      def value: Either[Error, (State, Value)]             = self.value
    }
  }
  object NewBehaviour {
    def apply[E1, E2 >: E1, O1, O2, S1, S2](wio0: WIO[E2, O2, S1, S2], value0: Either[E1, (S1, O1)]) = new NewBehaviour[E2, O2, S2] {
      override type State = S1
      override type Error = E1
      override type Value = O1
      override def wio: WIO[E2, O2, State, S2]          = wio0
      override def value: Either[Error, (State, Value)] = value0
    }
  }

  trait NewValue[+E, +O, +S] extends NextWfState[E, O, S] {
    def value: Either[E, (S, O)]
  }
  object NewValue {
    def apply[E, O, S](value0: Either[E, (S, O)]) = new NewValue[E, O, S] {
      override def value: Either[E, (S, O)] = value0
    }
  }

}

trait WfAndState {
  type Err <: NextError
  type StIn
  type StOut
  type NextValue
  type NextError
  def wio: Option[WIO[NextError, NextValue, StIn, StOut]]
  type Value
  val value: Either[Err, (StIn, Value)]
}

object WfAndState {

  type T[E, O1, SOut] = WfAndState { type NextError = E; type StOut = SOut; type NextValue = O1 }

  def apply[E1, E2 >: E1, O1, O2, S1, S2](wio0: Option[WIO[E2, O2, S1, S2]], value0: Either[E1, (S1, O1)]): WfAndState.T[E2, O2, S2] =
    new WfAndState {
      override type Err       = E1
      override type StIn      = S1
      override type StOut     = S2
      override type NextValue = O2
      override type NextError = E2
      override def wio: Option[WIO[NextError, NextValue, StIn, StOut]] = wio0
      override type Value = O1
      override val value: Either[Err, (StIn, Value)] = value0
    }

  def widenErr[E1, E2 >: E1, O, SOut](w: WfAndState.T[E1, O, SOut]): WfAndState.T[E2, O, SOut] = new WfAndState {
    override type Err       = w.Err
    override type StIn      = w.StIn
    override type StOut     = w.StOut
    override type NextValue = w.NextValue
    override type NextError = E2
    override def wio: Option[WIO[NextError, NextValue, StIn, StOut]] = w.wio
    override type Value = w.Value
    override val value: Either[Err, (StIn, Value)] = w.value
  }
}
