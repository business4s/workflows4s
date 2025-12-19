package workflows4s.runtime

import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.SignalDef
import workflows4s.wio.model.WIOExecutionProgress

class MappedWorkflowInstance[F[_], G[_], State](val base: WorkflowInstance[F, State], map: [t] => F[t] => G[t]) extends WorkflowInstance[G, State] {

  override def id: WorkflowInstanceId = base.id

  override def queryState(): G[State] = map(base.queryState())

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): G[Either[UnexpectedSignal, Resp]] =
    map(base.deliverSignal(signalDef, req))

  override def wakeup(): G[Unit] = map(base.wakeup())

  override def getProgress: G[WIOExecutionProgress[State]] = map(base.getProgress)

  def getExpectedSignals: G[List[SignalDef[?, ?]]] = map(base.getExpectedSignals)
}
