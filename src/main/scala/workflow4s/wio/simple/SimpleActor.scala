package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import workflow4s.wio.{ActiveWorkflow, SignalDef, SignalResponse}

class SimpleActor[State](var wf: ActiveWorkflow[State])(implicit IORuntime: IORuntime) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] =
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value) =>
        val (newWf, resp) = value.unsafeRunSync()
        wf = newWf
        SimpleActor.SignalResponse.Ok(resp)
      case SignalResponse.UnexpectedSignal() => SimpleActor.SignalResponse.UnexpectedSignal
    }

}

object SimpleActor {
  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Ok[Resp](result: Resp) extends SignalResponse[Resp]
    case object UnexpectedSignal      extends SignalResponse[Nothing]
  }
}
