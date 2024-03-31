package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.*

abstract class ActiveWorkflow(val interpreter: Interpreter) {
  type State
  type Output
  type Error
  val value: Either[Error, (State, Output)]
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
  
  private def errOrState = value.map(_._1)

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Resp] =
    m.SignalEvaluator.handleSignal(signalDef, req, wioHack, errOrState, interpreter)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]   =
    m.QueryEvaluator.handleQuery(signalDef, req, wioHack, errOrState)
  def handleEvent(event: Any): EventResponse                                                   =
    m.EventEvaluator.handleEvent(event, wioHack, errOrState, interpreter)
  def proceed(runIO: Boolean): ProceedResponse                                                 =
    m.ProceedEvaluator.proceed(wioHack, errOrState, interpreter, runIO)

  def getDesc = m.CurrentStateEvaluator.getCurrentStateDescription(wioHack)

}

object ActiveWorkflow {

  def apply[St, O, E](wio0: WIOT.Total[St], interpreter: Interpreter, value0: Either[E, (St, O)]) = new ActiveWorkflow(interpreter) {
    override type State = St
    override def wio: WIOT.Total[St] = wio0
    override type Output = O
    override type Error  = E
    override val value: Either[Error, (State, Output)] = value0
  }
}


