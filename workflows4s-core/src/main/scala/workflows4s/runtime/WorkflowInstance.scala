package workflows4s.runtime

import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.SignalDef

trait WorkflowInstance[F[_], State] {

  def queryState(): F[State]

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]]

  def wakeup(): F[Unit]

}

object WorkflowInstance {

  case class UnexpectedSignal(signalDef: SignalDef[?, ?])

}
