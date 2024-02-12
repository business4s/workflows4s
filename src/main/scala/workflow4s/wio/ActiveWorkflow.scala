package workflow4s.wio

case class ActiveWorkflow[St, +Out](state: St, wio: WIO.Total[St], interpreter: Interpreter[St], value: Out) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[St, Resp] =
    interpreter.handleSignal[Req, Resp, Any](signalDef, req, wio, state)
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]       =
    interpreter.handleQuery[Req, Resp, Nothing, Any](signalDef, req, wio, state)

}
