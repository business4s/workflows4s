package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.internal.CurrentStateEvaluator
import workflow4s.wio.{ActiveWorkflow, SignalDef}

class SimpleActor( /*private*/ var wf: ActiveWorkflow)(implicit IORuntime: IORuntime) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] =
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value)          =>
        val (newWf, resp) = value.unsafeRunSync()
        wf = newWf
        proceed()
        SimpleActor.SignalResponse.Ok(resp)
      case SignalResponse.UnexpectedSignal() => SimpleActor.SignalResponse.UnexpectedSignal(wf.getDesc)
    }
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.QueryResponse[Resp]               =
    wf.handleQuery(signalDef)(req) match {
      case QueryResponse.Ok(value)         => SimpleActor.QueryResponse.Ok(value)
      case QueryResponse.UnexpectedQuery() => SimpleActor.QueryResponse.UnexpectedQuery(wf.getDesc)
    }

  def handleEvent(event: Any): SimpleActor.EventResponse = wf.handleEvent(event) match {
    case EventResponse.Ok(newFlow)       =>
      wf = newFlow
      SimpleActor.EventResponse.Ok
    case EventResponse.UnexpectedEvent() => SimpleActor.EventResponse.UnexpectedEvent(wf.getDesc)
  }
  def proceed(): Unit                                    = wf.proceed match {
    case ProceedResponse.Executed(newFlowIO) =>
      wf = newFlowIO.unsafeRunSync()
      proceed()
    case ProceedResponse.Noop()              => ()
  }

}

object SimpleActor {
  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Ok[Resp](result: Resp)        extends SignalResponse[Resp]
    case class UnexpectedSignal(msg: String) extends SignalResponse[Nothing]
  }

  sealed trait EventResponse
  object EventResponse {
    case object Ok                          extends EventResponse
    case class UnexpectedEvent(msg: String) extends EventResponse
  }

  sealed trait QueryResponse[+Resp]
  object QueryResponse {
    case class Ok[Resp](result: Resp)       extends QueryResponse[Resp]
    case class UnexpectedQuery(msg: String) extends QueryResponse[Nothing]
  }
}
