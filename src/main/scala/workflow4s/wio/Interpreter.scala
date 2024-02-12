package workflow4s.wio

import cats.effect.IO
import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.WIO.HandleSignal

class Interpreter[St](journal: JournalPersistance) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Nothing, Any, St], state: St): SignalResponse[St, Resp] = {
    def go(wio: WIO.Total[St]): Option[IO[(ActiveWorkflow[St, Any], Resp)]] = {
      wio match {
        case x @ WIO.HandleSignal(_, _)    => x.expects(signalDef).map(doHandleSignal(req, _, state))
        case or @ WIO.Or(first, second)    =>
          go(first)
            .map(_.map({ case (wf, resp) => preserveOr1(or, wf, false) -> resp }))
            .orElse(go(second).map(_.map({ case (wf, resp) => preserveOr1(or, wf, true) -> resp })))
        case WIO.FlatMap(current, getNext) => go(current).map(wfIO => wfIO.map({ case (wf, resp) => wf.copy(wio = getNext(wf.value)) -> resp }))
        case WIO.Map(current, transform)   => go(current).map(wfIO => wfIO.map({ case (wf, resp) => wf.copy(value = transform(wf.value)) -> resp }))
        case _                             => None
      }
    }
    go(wio).map(SignalResponse.Ok(_)).getOrElse(SignalResponse.UnexpectedSignal())
  }

  def handleQuery[Req, Resp, Err, Out](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Err, Any, St], state: St): QueryResponse[Resp] = {
    def go(wio: WIO[Err, Any, St]): Option[Resp] = wio match {
      case x @ WIO.HandleQuery(_)  => x.expects(signalDef).map(doHandleQuery(req, _, state))
      case WIO.Or(first, second)   => go(first).orElse(go(second))
      case WIO.FlatMap(current, _) => go(current)
      case WIO.Map(base, f)        => go(base)
      case WIO.Noop()              => None
      case WIO.HandleSignal(_, _)  => None
    }
    go(wio).map(QueryResponse.Ok(_)).getOrElse(QueryResponse.UnexpectedQuery())
  }

  def handleEvent(event: Any, wio: WIO.Total[St], state: St): EventResponse[St] = {
    def go(wio: WIO.Total[St]): Option[ActiveWorkflow[St, Any]] = wio match {
      case HandleSignal(_, evtHandler) =>
        evtHandler
          .expects(event)
          .map(validatedEvent => {
            val (newState, resp) = evtHandler.handle(state, validatedEvent)
            ActiveWorkflow(newState, WIO.Noop(), this, resp)
          })
      case or @ WIO.Or(first, second)  =>
        // TODO we could alert in case both branches expects the event
        go(first)
          .map(preserveOr1(or, _, false))
          .orElse(go(second).map(preserveOr1(or, _, true)))
      case WIO.FlatMap(current, getNext) => go(current).map(wf => wf.copy(wio = getNext(wf.value)))
      case WIO.Map(current, transform)   => go(current).map(wf => wf.copy(value = transform(wf.value)))
      case WIO.HandleQuery(_)            => None
      case WIO.Noop()                    => None
    }
    go(wio).map(EventResponse.Ok(_)).getOrElse(EventResponse.UnexpectedEvent())
  }

  private def preserveOr1[Resp](
      or: WIO.Or[Nothing, Any, St],
      newWf: ActiveWorkflow[St, Resp],
      firstToStay: Boolean,
  ): ActiveWorkflow[St, Resp] = newWf.copy(wio = if (firstToStay) WIO.Or(or.first, newWf.wio) else WIO.Or(newWf.wio, or.second))

  private def doHandleSignal[Req, Resp, Evt](
      req: Req,
      handler: WIO.HandleSignal[Req, St, Evt, Resp],
      state: St,
  ): IO[(ActiveWorkflow[St, Any], Resp)] = {
    for {
      evt             <- handler.sigHandler.handle(state, req)
      _               <- journal.save(evt)(handler.evtHandler.jw)
      (newState, resp) = handler.evtHandler.handle(state, evt)
    } yield ActiveWorkflow(newState, WIO.Noop(), this, resp) -> resp
  }

  private def doHandleQuery[Req, Resp, Evt](req: Req, handler: WIO.HandleQuery[Req, St, Resp], state: St): Resp =
    handler.queryHandler.handle(state, req)

}

object Interpreter {

  sealed trait EventResponse[St]
  object EventResponse {
    case class Ok[St](newFlow: ActiveWorkflow[St, Any]) extends EventResponse[St]
    case class UnexpectedEvent[St]()                    extends EventResponse[St]
  }

}
