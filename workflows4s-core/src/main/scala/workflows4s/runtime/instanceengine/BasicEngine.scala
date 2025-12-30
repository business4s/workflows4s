package workflows4s.runtime.instanceengine

import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

trait BasicEngine[F[_]](using Effect[F]) extends WorkflowInstanceEngine[F] {
  import Effect.*
  protected def now: F[Instant]

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[WCEvent[Ctx], F]] = {
    now.map(workflow.proceed)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp, F]] = workflow.handleSignal(signalDef)(req).pure

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): F[Option[ActiveWorkflow[F, Ctx]]] =
    workflow.handleEvent(event).pure

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): WCState[Ctx] = workflow.liveState

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): WIOExecutionProgress[WCState[Ctx]] = workflow.progress

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): List[SignalDef[?, ?]] =
    workflow.expectedSignals

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = Set.empty.pure
}
