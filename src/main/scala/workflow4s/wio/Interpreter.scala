package workflow4s.wio

import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.WIO.HandleSignal

class Interpreter[St](journal: JournalPersistance) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Nothing, Any, St], state: St): SignalResponse[St, Resp] =
    wio match {
      case x @ WIO.HandleSignal(_, _) =>
        x.expects(signalDef) match {
          case Some(handleSignalWio) => doHandleSignal(req, handleSignalWio, state)
          case None                  => SignalResponse.UnexpectedSignal()
        }
      case or @ WIO.Or(first, second) =>
        handleSignal(signalDef, req, first, state) match {
          case x @ SignalResponse.Ok(_)          => preserveOr(or, x, false)
          case SignalResponse.UnexpectedSignal() =>
            handleSignal(signalDef, req, second, state) match {
              case x @ SignalResponse.Ok(_)          => preserveOr(or, x, true)
              case SignalResponse.UnexpectedSignal() => SignalResponse.UnexpectedSignal()
            }
        }
      case _                          => SignalResponse.UnexpectedSignal()
    }

  def handleQuery[Req, Resp, Err, Out](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Err, Out, St], state: St): QueryResponse[Resp] =
    wio match {
      case x @ WIO.HandleQuery(_) =>
        x.expects(signalDef)
          .map(handler => doHandleQuery(req, handler, state))
          .getOrElse(QueryResponse.UnexpectedQuery())
      case WIO.Or(first, second)  =>
        handleQuery(signalDef, req, first, state) match {
          case x @ QueryResponse.Ok(_)         => x
          case QueryResponse.UnexpectedQuery() => handleQuery(signalDef, req, second, state)
        }
      case _                      => QueryResponse.UnexpectedQuery()
    }

  def handleEvent(event: Any, wio: WIO.Total[St], state: St): EventResponse[St] = {
    def go(wio: WIO.Total[St]): Option[ActiveWorkflow[St, Any]] = wio match {
      case HandleSignal(_, evtHandler)   =>
        evtHandler
          .expects(event)
          .map(validatedEvent => {
            val (newState, resp) = evtHandler.handle(state, validatedEvent)
            ActiveWorkflow(newState, WIO.Noop(), this, resp)
          })
      case WIO.HandleQuery(queryHandler) => None
      case or @ WIO.Or(first, second)    =>
        // TODO we could alert in case both branches expects the event
        go(first)
          .map(preserveOr1(or, _, false))
          .orElse(go(second))
          .map(preserveOr1(or, _, true))
      case WIO.Noop()                    => None
    }
    go(wio).map(EventResponse.Ok(_)).getOrElse(EventResponse.UnexpectedEvent())
  }

  private def preserveOr[Resp](
      or: WIO.Or[Nothing, Any, St],
      resp: SignalResponse.Ok[St, Resp],
      firstToStay: Boolean,
  ): SignalResponse.Ok[St, Resp] = SignalResponse.Ok(resp.value.map(preserveOr1(or, _, firstToStay)))

  private def preserveOr1[Resp](
      or: WIO.Or[Nothing, Any, St],
      newWf: ActiveWorkflow[St, Resp],
      firstToStay: Boolean,
  ): ActiveWorkflow[St, Resp] = newWf.copy(wio = if (firstToStay) WIO.Or(or.first, newWf.wio) else WIO.Or(newWf.wio, or.second))

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

object Interpreter {

  sealed trait EventResponse[St] {
    def asOk: Option[EventResponse.Ok[St]] = this match {
      case x @ EventResponse.Ok(_)         => Some(x)
      case EventResponse.UnexpectedEvent() => None
    }
  }
  object EventResponse           {
    case class Ok[St](newFlow: ActiveWorkflow[St, Any]) extends EventResponse[St]
    case class UnexpectedEvent[St]()                    extends EventResponse[St]
  }

}
