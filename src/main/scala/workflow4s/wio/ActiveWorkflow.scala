package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.*

abstract class ActiveWorkflow {
  type Context <: WorkflowContext
  val context: Context
  type State
  type Error = Any
  val state: State
  def wio: Context#WIO[State, Nothing, Any]
  def interpreter: Interpreter[Context]

  private lazy val proceedEvaluator      = new ProceedEvaluator[Context](context, interpreter)
  private lazy val signalEvaluator       = new SignalEvaluator[Context](context, interpreter)
  private lazy val eventEvaluator        = new EventEvaluator[Context](context, interpreter)
  private lazy val queryEvaluator        = new QueryEvaluator[Context](context, interpreter)
  private lazy val currentStateEvaluator = new CurrentStateEvaluator(context)

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Context, Resp] =
    signalEvaluator.handleSignal(signalDef, req, wio, state)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]            =
    queryEvaluator.handleQuery(signalDef, req, wio, state)
  def handleEvent(event: Context#Event): EventResponse[Context]                                         =
    eventEvaluator.handleEvent(event, wio, state)
  def proceed(runIO: Boolean): ProceedResponse[Context]                                                 =
    proceedEvaluator.proceed(wio, state, runIO)

  def getDesc = currentStateEvaluator.getCurrentStateDescription(wio)
}

object ActiveWorkflow {

  type ForCtx[Ctx] = ActiveWorkflow { type Context = Ctx }

  // TODO Out will become Ctx#State
  def apply[Ctx <: WorkflowContext, In, Out](wio0: Ctx#WIO[In, Nothing, Out], value0: In)(
      interpreter0: Interpreter[Ctx],
  ): ActiveWorkflow.ForCtx[Ctx] =
    new ActiveWorkflow {
      override type Context = Ctx
      override type State   = In
      override val state: State                       = value0
      override def wio: Context#WIO[In, Nothing, Out] = wio0
      override val context: Ctx                       = wio0.context
      override def interpreter: Interpreter[Ctx]      = interpreter0
    }
}
