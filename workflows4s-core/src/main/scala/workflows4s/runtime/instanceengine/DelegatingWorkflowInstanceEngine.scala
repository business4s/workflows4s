package workflows4s.runtime.instanceengine

import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

trait DelegatingWorkflowInstanceEngine[F[_]] extends WorkflowInstanceEngine[F] {
  protected def delegate: WorkflowInstanceEngine[F]

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WCState[Ctx]]                        = delegate.queryState(workflow)
  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = delegate.getProgress(workflow)
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[List[SignalDef[?, ?]]]       = delegate.getExpectedSignals(workflow)

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[WCEvent[Ctx], F]] =
    delegate.triggerWakeup(workflow)

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp, F]] = delegate.handleSignal(workflow, signalDef, req)

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): F[Option[ActiveWorkflow[F, Ctx]]] =
    delegate.handleEvent(workflow, event)

  override def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): F[ActiveWorkflow[F, Ctx]] =
    delegate.processEvent(workflow, event)

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] =
    delegate.onStateChange(oldState, newState)

}
