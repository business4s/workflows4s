package workflow4s.wio

import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.*

abstract class ActiveWorkflow {
  type Context <: WorkflowContext
  type CurrentState <: WCState[Context]
  type Error = Any
  val state: CurrentState
  def wio: WIO[CurrentState, Nothing, WCState[Context], Context]
  def interpreter: Interpreter[Context]

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[Context, Resp] =
    SignalEvaluator.handleSignal(signalDef, req, wio, state, interpreter)
  def handleEvent(event: WCEvent[Context]): EventResponse[Context]                                         =
    EventEvaluator.handleEvent(event, wio, state, interpreter)
  def proceed(runIO: Boolean): ProceedResponse[Context]                                                 =
    ProceedEvaluator.proceed(wio, state, runIO, interpreter)

  def getDesc = CurrentStateEvaluator.getCurrentStateDescription(wio)
  def getState: WCState[Context] = state
}

object ActiveWorkflow {

  type ForCtx[Ctx] = ActiveWorkflow { type Context = Ctx }

  // TODO Out will become Ctx#State
  def apply[Ctx <: WorkflowContext, In <: WCState[Ctx]](wio0: WIO[In, Nothing, WCState[Ctx], Ctx], value0: In)(
      interpreter0: Interpreter[Ctx],
  ): ActiveWorkflow.ForCtx[Ctx] =
    new ActiveWorkflow {
      override type Context      = Ctx
      override type CurrentState = In
      override val state: CurrentState                                 = value0
      override def wio: WIO[CurrentState, Nothing, WCState[Context], Ctx] = wio0
      override def interpreter: Interpreter[Ctx]                       = interpreter0
    }
}
