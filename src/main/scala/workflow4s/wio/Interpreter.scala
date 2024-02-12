package workflow4s.wio

import workflow4s.wio.WIO.HandleSignal

class Interpreter[St](journal: JournalPersistance) {

  def handleSignal[Req, Resp, Out](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Nothing, Out, St], state: St): SignalResponse[St, Resp] =
    wio match {
      case x @ WIO.HandleSignal(_, _) =>
        x.expects(signalDef) match {
          case Some(handleSignalWio) => doHandleSignal(req, handleSignalWio, state)
          case None                  => SignalResponse.UnexpectedSignal()
        }
      case WIO.Or(first, second)      =>
        def preserveOr(
            resp: SignalResponse.Ok[St, Resp],
            firstToStay: Boolean,
        ): SignalResponse.Ok[St, Resp] =
          SignalResponse.Ok(
            resp.value.map(newWf => newWf.copy(wio = if (firstToStay) WIO.Or(first, newWf.wio) else WIO.Or(newWf.wio, second))),
          )
        handleSignal(signalDef, req, first, state) match {
          case x @ SignalResponse.Ok(_)          => preserveOr(x, false)
          case SignalResponse.UnexpectedSignal() =>
            handleSignal(signalDef, req, second, state) match {
              case x @ SignalResponse.Ok(_)          => preserveOr(x, true)
              case SignalResponse.UnexpectedSignal() => SignalResponse.UnexpectedSignal()
            }
        }
      case _                          => SignalResponse.UnexpectedSignal()
    }

  def handleQuery[Req, Resp, Err, Out](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Err, Out, St], state: St): QueryResponse[Resp] =
    wio match {
      case x @ WIO.HandleQuery(_) =>
        x.expects(signalDef) match {
          case Some(handleQueryWio) => doHandleQuery(req, handleQueryWio, state)
          case None                 => QueryResponse.UnexpectedQuery()
        }
      case WIO.Or(first, second)  =>
        handleQuery(signalDef, req, first, state) match {
          case x @ QueryResponse.Ok(_)         => x
          case QueryResponse.UnexpectedQuery() => handleQuery(signalDef, req, second, state)
        }
      case _                      => QueryResponse.UnexpectedQuery()
    }

  private def doHandleSignal[Req, Resp, Evt](req: Req, handler: WIO.HandleSignal[Req, St, Evt, Resp], state: St): SignalResponse.Ok[St, Resp] = {
    val io = for {
      evt             <- handler.sigHandler.handle(state, req)
      _               <- journal.save(evt)(handler.evtHandler.jw)
      (newState, resp) = handler.evtHandler.handle(state, evt)
    } yield ActiveWorkflow(newState, WIO.Noop(), this, resp)
    SignalResponse.Ok(io)
  }

  private def doHandleQuery[Req, Resp, Evt](req: Req, handler: WIO.HandleQuery[Req, St, Resp], state: St): QueryResponse.Ok[Resp] = {
    QueryResponse.Ok(handler.queryHandler.handle(state, req))
  }

}
