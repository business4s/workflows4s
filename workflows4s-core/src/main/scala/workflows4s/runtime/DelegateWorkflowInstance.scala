package workflows4s.runtime

import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.SignalDef
import workflows4s.wio.model.WIOExecutionProgress

trait DelegateWorkflowInstance[F[_], State] extends WorkflowInstance[F, State] {

  def delegate: WorkflowInstance[F, State]

  override def id: WorkflowInstanceId = delegate.id

  override def queryState(): F[State] = delegate.queryState()

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]] =
    delegate.deliverSignal(signalDef, req)

  override def wakeup(): F[Unit] = delegate.wakeup()

  override def getProgress: F[WIOExecutionProgress[State]] = delegate.getProgress
}
