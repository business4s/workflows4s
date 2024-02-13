package workflow4s.wio

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.WIO.{EventHandler, HandleSignal}

class Interpreter[St](journal: JournalPersistance) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Nothing, Any, St], state: St): SignalResponse[St, Resp] = {
    def go(wio: WIO.Total[St]): Option[IO[(ActiveWorkflow[St, Any], Resp)]] = {
      wio match {
        case x @ WIO.HandleSignal(_, _)    => x.expects(signalDef).map(doHandleSignal(req, _, state))
        case or @ WIO.Or(first, second)    =>
          go(first)
            .map(_.map({ case (wf, resp) => preserveOr(or, wf, false) -> resp }))
            .orElse(go(second).map(_.map({ case (wf, resp) => preserveOr(or, wf, true) -> resp })))
        case WIO.FlatMap(current, getNext) => go(current).map(wfIO => wfIO.map({ case (wf, resp) => wf.copy(wio = getNext(wf.value)) -> resp }))
        case WIO.Map(current, transform)   => go(current).map(wfIO => wfIO.map({ case (wf, resp) => wf.copy(value = transform(wf.value)) -> resp }))
        case WIO.HandleQuery(_)            => None
        case WIO.RunIO(_, _)               => None
        case WIO.Noop()                    => None
      }
    }
    go(wio).map(SignalResponse.Ok(_)).getOrElse(SignalResponse.UnexpectedSignal())
  }

  def handleQuery[Req, Resp, Err, Out](signalDef: SignalDef[Req, Resp], req: Req, wio: WIO[Err, Any, St], state: St): QueryResponse[Resp] = {
    def doHandleQuery(handler: WIO.HandleQuery[Req, St, Resp]): Resp = handler.queryHandler.handle(state, req)
    def go(wio: WIO[Err, Any, St]): Option[Resp]                     = wio match {
      case x @ WIO.HandleQuery(_)  => x.expects(signalDef).map(doHandleQuery)
      case WIO.Or(first, second)   => go(first).orElse(go(second))
      case WIO.FlatMap(current, _) => go(current)
      case WIO.Map(base, _)        => go(base)
      case WIO.Noop()              => None
      case WIO.RunIO(_, _)         => None
      case WIO.HandleSignal(_, _)  => None
    }
    go(wio).map(QueryResponse.Ok(_)).getOrElse(QueryResponse.UnexpectedQuery())
  }

  def handleEvent(event: Any, wio: WIO.Total[St], state: St): EventResponse[St] = {
    def doHandle[Evt, Out](handler: EventHandler[Evt, St, Out]) =
      handler
        .expects(event)
        .map(validatedEvent => {
          val (newState, resp) = handler.handle(state, validatedEvent)
          ActiveWorkflow(newState, WIO.Noop(), this, resp)
        })
    def go(wio: WIO.Total[St]): Option[ActiveWorkflow[St, Any]] = wio match {
      case HandleSignal(_, evtHandler)   => doHandle(evtHandler)
      case WIO.RunIO(_, evtHandler)      => doHandle(evtHandler)
      case or @ WIO.Or(first, second)    =>
        // TODO we could alert in case both branches expects the event
        go(first)
          .map(preserveOr(or, _, false))
          .orElse(go(second).map(preserveOr(or, _, true)))
      case WIO.FlatMap(current, getNext) => go(current).map(wf => wf.copy(wio = getNext(wf.value)))
      case WIO.Map(current, transform)   => go(current).map(wf => wf.copy(value = transform(wf.value)))
      case WIO.HandleQuery(_)            => None
      case WIO.Noop()                    => None
    }
    go(wio).map(EventResponse.Ok(_)).getOrElse(EventResponse.UnexpectedEvent())
  }

  def proceed(wio: WIO.Total[St], state: St): ProceedResponse[St] = {
    def go(wio: WIO.Total[St]): Option[IO[ActiveWorkflow[St, Any]]] = wio match {
      case x @ WIO.RunIO(_, _)           => doHandleIO(x, state).some
      case HandleSignal(_, _)            => None
      case or @ WIO.Or(first, second)    =>
        go(first)
          .map(_.map(wf => preserveOr(or, wf, false)))
          .orElse(go(second).map(_.map(wf => preserveOr(or, wf, true))))
      case WIO.FlatMap(current, getNext) => go(current).map(wfIO => wfIO.map(wf => wf.copy(wio = getNext(wf.value))))
      case WIO.Map(current, transform)   => go(current).map(wfIO => wfIO.map(wf => wf.copy(value = transform(wf.value))))
      case WIO.HandleQuery(_)            => None
      case WIO.Noop()                    => None
    }
    go(wio).map(ProceedResponse.Executed(_)).getOrElse(ProceedResponse.Noop())
  }

  private def preserveOr[Resp](
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
  private def doHandleIO[Req, Resp, Evt](
      handler: WIO.RunIO[St, Evt, Resp],
      state: St,
  ): IO[ActiveWorkflow[St, Any]] = {
    for {
      evt             <- handler.buildIO(state)
      _               <- journal.save(evt)(handler.evtHandler.jw)
      (newState, resp) = handler.evtHandler.handle(state, evt)
    } yield ActiveWorkflow(newState, WIO.Noop(), this, resp)
  }

}

object Interpreter {

  sealed trait EventResponse[St]
  object EventResponse {
    case class Ok[St](newFlow: ActiveWorkflow[St, Any]) extends EventResponse[St]
    case class UnexpectedEvent[St]()                    extends EventResponse[St]
  }

  sealed trait ProceedResponse[St]
  object ProceedResponse {
    case class Executed[St](newFlow: IO[ActiveWorkflow[St, Any]]) extends ProceedResponse[St]
    case class Noop[St]()                                         extends ProceedResponse[St]
  }

  sealed trait SignalResponse[St, Resp]
  object SignalResponse {
    case class Ok[St, Resp](value: IO[(ActiveWorkflow[St, Any], Resp)]) extends SignalResponse[St, Resp]
    case class UnexpectedSignal[St, Resp]()                             extends SignalResponse[St, Resp]
  }

  sealed trait QueryResponse[Resp]
  object QueryResponse {
    case class Ok[Resp](value: Resp)   extends QueryResponse[Resp]
    case class UnexpectedQuery[Resp]() extends QueryResponse[Resp]
  }

}
