package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.*

abstract class ActiveWorkflow {
  type Context <: WorkflowContext
  type CurrentState
  type Error = Any
  val state: CurrentState
  def wio: WIO[CurrentState, Nothing, Context#State, Context]
  def interpreter: Interpreter[Context]

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Context, Resp] =
    SignalEvaluator.handleSignal(signalDef, req, wio, state, interpreter)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]            =
    QueryEvaluator.handleQuery(signalDef, req, wio, state)
  def handleEvent(event: Context#Event): EventResponse[Context]                                         =
    EventEvaluator.handleEvent(event, wio, state, interpreter)
  def proceed(runIO: Boolean): ProceedResponse[Context]                                                 =
    ProceedEvaluator.proceed(wio, state, runIO, interpreter)

  def getDesc = CurrentStateEvaluator.getCurrentStateDescription(wio)
}

object ActiveWorkflow {

  type ForCtx[Ctx] = ActiveWorkflow { type Context = Ctx }

  // TODO Out will become Ctx#State
  def apply[Ctx <: WorkflowContext, In](wio0: WIO[In, Nothing, Ctx#State, Ctx], value0: In)(
      interpreter0: Interpreter[Ctx],
  ): ActiveWorkflow.ForCtx[Ctx] =
    new ActiveWorkflow {
      override type Context      = Ctx
      override type CurrentState = In
      override val state: CurrentState                                 = value0
      override def wio: WIO[CurrentState, Nothing, Context#State, Ctx] = wio0
      override def interpreter: Interpreter[Ctx]                       = interpreter0
    }
}
