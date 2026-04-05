package workflows4s.runtime.instanceengine

import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

trait DelegatingWorkflowInstanceEngine[F[_]] extends WorkflowInstanceEngine[F] {
  protected def delegate: WorkflowInstanceEngine[F]

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]]                                                         = delegate.queryState(workflow)
  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]                                  = delegate.getProgress(workflow)
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]] =
    delegate.getExpectedSignals(workflow, includeRedeliverable)

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[WCEffect[Ctx], WCEvent[Ctx]]] =
    delegate.triggerWakeup(workflow)

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEffect[Ctx], WCEvent[Ctx], Resp]] = delegate.handleSignal(workflow, signalDef, req)

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]] =
    delegate.handleEvent(workflow, event)

  override def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[ActiveWorkflow[Ctx]] =
    delegate.processEvent(workflow, event)

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] =
    delegate.onStateChange(oldState, newState)

}
