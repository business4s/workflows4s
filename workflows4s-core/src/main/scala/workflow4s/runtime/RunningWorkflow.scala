package workflow4s.runtime

import workflow4s.runtime.RunningWorkflow.UnexpectedSignal
import workflow4s.wio.SignalDef

trait RunningWorkflow[F[_], State] {

  def queryState(): F[State]

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]]

  def wakeup(): F[Unit]

}

object RunningWorkflow {

  case class UnexpectedSignal(signalDef: SignalDef[?, ?])

}
