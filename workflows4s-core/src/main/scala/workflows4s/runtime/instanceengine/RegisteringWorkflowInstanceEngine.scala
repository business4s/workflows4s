package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

// TODO check how this engine interacts with non-greedy mode.
//  What happens when there is more to execute? What is unexpected signall appers between wakeups?
class RegisteringWorkflowInstanceEngine(protected val delegate: WorkflowInstanceEngine, registry: WorkflowRegistry.Agent)
    extends DelegatingWorkflowInstanceEngine {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WakeupResult[WCEvent[Ctx], IO]] = {
    for {
      prevResult <- super.triggerWakeup(workflow)
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[SignalResult[WCEvent[Ctx], Resp, IO]] = {
    for {
      prevResult <- super.handleSignal(workflow, signalDef, req)
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <* registerNotRunningInstance(newState)
  }

  private def registeringRunningInstance(workflow: ActiveWorkflow[?], hasFollowup: Boolean) =
    if hasFollowup then registry.upsertInstance(workflow, WorkflowRegistry.ExecutionStatus.Running)
    else registerNotRunningInstance(workflow)

  private def registerNotRunningInstance(workflow: ActiveWorkflow[?]): IO[Unit] = {
    val status =
      if workflow.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(workflow, status)
  }
}
