package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.*

abstract class ActiveWorkflow(val interpreter: Interpreter) {
  type State
  type Error
  val state: Either[Error, State]
  def wio: WIOT.Total[State]

  val m = new VisitorModule
    with SignalEvaluatorModule
    with EventEvaluatorModule
    with QueryEvaluatorModule
    with ProceedEvaluatorModule
    with CurrentStateEvaluatorModule {
    override val c: WorkflowContext = wio.context
  }

  val wioHack: m.c.WIO.Total[State] = wio.asInstanceOf
  
  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Resp] =
    m.SignalEvaluator.handleSignal(signalDef, req, wioHack, state, interpreter)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]   =
    m.QueryEvaluator.handleQuery(signalDef, req, wioHack, state)
  def handleEvent(event: Any): EventResponse                                                   =
    m.EventEvaluator.handleEvent(event, wioHack, state, interpreter)
  def proceed(runIO: Boolean): ProceedResponse                                                 =
    m.ProceedEvaluator.proceed(wioHack, state, interpreter, runIO)

  def getDesc = m.CurrentStateEvaluator.getCurrentStateDescription(wioHack)
}

object ActiveWorkflow {

  def apply[St, E](wio0: WIOT.Total[St], interpreter: Interpreter, value0: Either[E, St]) = new ActiveWorkflow(interpreter) {
    override def wio: WIOT.Total[St] = wio0
    override type State = St
    override type Error  = E
    override val state: Either[Error, State] = value0
  }
}


