package workflows4s.runtime

import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.SignalDef
import workflows4s.wio.internal.SignalWrapper
import workflows4s.wio.model.WIOExecutionProgress

trait WorkflowInstance[F[_], State] {

  def queryState(): F[State]

  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]]
  def deliverWrappedSignal[Req, Resp, ElemId](
      signalWrapper: SignalWrapper[ElemId],
      elemId: ElemId,
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[Either[UnexpectedSignal, Resp]] = {
    val w = signalWrapper.wrapRequest(elemId, req, signalDef)
    deliverSignal(w.sigDef, w.req)
  }

  def wakeup(): F[Unit]

  def getProgress: F[WIOExecutionProgress[State]]

}

object WorkflowInstance {

  case class UnexpectedSignal(signalDef: SignalDef[?, ?])

}
