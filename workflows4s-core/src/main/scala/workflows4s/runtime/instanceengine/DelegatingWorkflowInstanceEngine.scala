package workflows4s.runtime.instanceengine

import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

trait DelegatingWorkflowInstanceEngine[F[_], Ctx <: WorkflowContext] extends WorkflowInstanceEngine[F, Ctx] {
  protected def delegate: WorkflowInstanceEngine[F, Ctx]

  def liftWCEffect: WCEffectLift[Ctx, F] = delegate.liftWCEffect

  def queryState(workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]]                                                         = delegate.queryState(workflow)
  def getProgress(workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]                                  = delegate.getProgress(workflow)
  def getExpectedSignals(workflow: ActiveWorkflow[Ctx], includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]] =
    delegate.getExpectedSignals(workflow, includeRedeliverable)

  override def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] =
    delegate.triggerWakeup(workflow)

  override def handleSignal[Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = delegate.handleSignal(workflow, signalDef, req)

  override def handleEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]] =
    delegate.handleEvent(workflow, event)

  override def processEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[ActiveWorkflow[Ctx]] =
    delegate.processEvent(workflow, event)

  override def scheduleRetry(workflow: ActiveWorkflow[Ctx], retryTime: java.time.Instant): F[Unit] =
    delegate.scheduleRetry(workflow, retryTime)

  override def onStateChange(
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] =
    delegate.onStateChange(oldState, newState)

}
