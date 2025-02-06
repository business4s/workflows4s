package workflows4s.runtime

import cats.~>
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.SignalDef
import workflows4s.wio.model.WIOExecutionProgress

class MappedWorkflowInstance[F[_], G[_], State](base: WorkflowInstance[F, State], map: F ~> G) extends WorkflowInstance[G, State] {

  override def queryState(): G[State] = map(base.queryState())

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): G[Either[UnexpectedSignal, Resp]] =
    map(base.deliverSignal(signalDef, req))

  override def wakeup(): G[Unit] = map(base.wakeup())
  
  override def getProgress: G[WIOExecutionProgress[State]] = map(base.getProgress)
}
