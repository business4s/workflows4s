package workflow4s.wio

case class ActiveWorkflow[St](state: St, wio: WIO[Nothing, Any, St], journal: JournalPersistance) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResponse[St, Resp] = wio match {
    case x: WIO.HandleSignal[_, St, _, _] =>
      x.expects(signalDef) match {
        case Some(handleSignalWio) =>
          val io = for {
            evt              <- handleSignalWio.sigHandler.handle(state, req)
            _                <- journal.save(evt)(handleSignalWio.evtHandler.jw)
            (newState, resp) = handleSignalWio.evtHandler.handle(state, evt)
          } yield ActiveWorkflow(newState, WIO.Noop(), journal) -> resp
          SignalResponse.Ok(io)
        case None => SignalResponse.UnexpectedSignal()
      }
    case _ => SignalResponse.UnexpectedSignal()
  }

}
