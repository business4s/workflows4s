package workflows4s.runtime.instanceengine

import cats.effect.{IO, SyncIO}
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.{ActiveWorkflow, SignalDef, WCEvent, WCState, WorkflowContext}

import java.time.Instant

trait DelegatingWorkflowInstanceEngine extends WorkflowInstanceEngine {
  protected def delegate: WorkflowInstanceEngine

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]]                        = delegate.queryState(workflow)
  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = delegate.getProgress(workflow)
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]]       = delegate.getExpectedSignals(workflow)

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[Option[IO[Either[Instant, WCEvent[Ctx]]]]] =
    delegate.triggerWakeup(workflow)

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[Option[IO[(WCEvent[Ctx], Resp)]]] = delegate.handleSignal(workflow, signalDef, req)

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[Ctx]]] =
    delegate.handleEvent(workflow, event)

  override def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[ActiveWorkflow[Ctx]] =
    delegate.processEvent(workflow, event)

  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): IO[Set[WorkflowInstanceEngine.PostExecCommand]] =
    delegate.onStateChange(oldState, newState)

}
