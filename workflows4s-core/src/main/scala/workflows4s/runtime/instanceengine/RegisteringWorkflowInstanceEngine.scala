package workflows4s.runtime.instanceengine

import cats.Monad
import cats.syntax.all.*
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

// TODO check how this engine interacts with non-greedy mode.
//  What happens when there is more to execute? What is unexpected signall appers between wakeups?
class RegisteringWorkflowInstanceEngine[F[_]: Monad, Ctx <: WorkflowContext](
    protected val delegate: WorkflowInstanceEngine[F, Ctx],
    registry: WorkflowRegistry.Agent[F],
) extends DelegatingWorkflowInstanceEngine[F, Ctx] {

  override def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[WCEffect[Ctx], WCEvent[Ctx]]] = {
    for {
      prevResult <- super.triggerWakeup(workflow)
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def handleSignal[Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEffect[Ctx], WCEvent[Ctx], Resp]] = {
    for {
      prevResult <- super.handleSignal(workflow, signalDef, req)
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def onStateChange(
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <* registerNotRunningInstance(newState)
  }

  private def registeringRunningInstance(workflow: ActiveWorkflow[?], hasFollowup: Boolean) =
    if hasFollowup then registry.upsertInstance(workflow, WorkflowRegistry.ExecutionStatus.Running)
    else registerNotRunningInstance(workflow)

  private def registerNotRunningInstance(workflow: ActiveWorkflow[?]): F[Unit] = {
    val status =
      if workflow.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(workflow, status)
  }
}
