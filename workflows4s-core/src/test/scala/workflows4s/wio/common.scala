package workflows4s.wio


object TestCtx extends WorkflowContext {
  type Event = String
  type State = String

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Ctx] = ActiveWorkflow(wio, state, None)
  }
}

