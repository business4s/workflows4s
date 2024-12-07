package workflow4s.runtime

import workflow4s.runtime.WorkflowInstance.UnexpectedSignal
import workflow4s.wio.SignalDef

trait WorkflowInstance[F[_], State] {

  def queryState(): F[State]

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]]

  def wakeup(): F[Unit]

}

object WorkflowInstance {

  case class UnexpectedSignal(signalDef: SignalDef[?, ?])

}
