package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.{ActiveWorkflow, SignalDef}

class SimpleActor( /*private*/ var wf: ActiveWorkflow)(implicit IORuntime: IORuntime) extends StrictLogging {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] = {
    logger.debug(s"Handling signal ${req}")
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value)          =>
        val (newWf, resp) = value.unsafeRunSync()
        wf = newWf
        logger.debug(s"Signal handled. Next state: ${newWf.getDesc}")
        proceed()
        SimpleActor.SignalResponse.Ok(resp)
      case SignalResponse.UnexpectedSignal() =>
        logger.debug(s"Unexpected signal ${req}. Wf: ${wf.getDesc}")
        SimpleActor.SignalResponse.UnexpectedSignal(wf.getDesc)
    }
  }

  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.QueryResponse[Resp] =
    wf.handleQuery(signalDef)(req) match {
      case QueryResponse.Ok(value)         => SimpleActor.QueryResponse.Ok(value)
      case QueryResponse.UnexpectedQuery() => SimpleActor.QueryResponse.UnexpectedQuery(wf.getDesc)
    }

  def handleEvent(event: Any): SimpleActor.EventResponse = {
    logger.debug(s"Handling event: ${event}")
    val resp = wf.handleEvent(event) match {
      case EventResponse.Ok(newFlow)       =>
        wf = newFlow
        SimpleActor.EventResponse.Ok
      case EventResponse.UnexpectedEvent() => SimpleActor.EventResponse.UnexpectedEvent(wf.getDesc)
    }
    logger.debug(s"Event response: ${resp}")
    resp
  }
  def proceed(): Unit = {
    logger.debug(s"Proceeding to the next step")
    wf.proceed match {
      case ProceedResponse.Executed(newFlowIO) =>
        wf = newFlowIO.unsafeRunSync()
        logger.debug(s"Proceeded. New wf: ${wf.getDesc}")
        proceed()
      case ProceedResponse.Noop()              =>
        logger.debug(s"Can't proceed. Wf: ${wf.getDesc}")
        ()
    }
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
