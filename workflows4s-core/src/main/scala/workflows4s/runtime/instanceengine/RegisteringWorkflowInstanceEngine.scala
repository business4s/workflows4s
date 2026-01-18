package workflows4s.runtime.instanceengine

import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

// TODO check how this engine interacts with non-greedy mode.
//  What happens when there is more to execute? What is unexpected signal appears between wakeups?
class RegisteringWorkflowInstanceEngine[F[_]](
    protected val delegate: WorkflowInstanceEngine[F],
    registry: WorkflowRegistry.Agent[F],
)(using E: Effect[F])
    extends DelegatingWorkflowInstanceEngine[F] {

  import Effect.*

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[WCEvent[Ctx], F]] = {
    for {
      prevResult <- super.triggerWakeup(workflow)
      // Note: .hasEffect is a method on WakeupResult
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp, F]] = {
    for {
      prevResult <- super.handleSignal(workflow, signalDef, req)
      _          <- registeringRunningInstance(workflow, prevResult.hasEffect)
    } yield prevResult
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <* registerNotRunningInstance(newState)
  }

  private def registeringRunningInstance(workflow: ActiveWorkflow[F, ?], hasFollowup: Boolean): F[Unit] =
    if hasFollowup then registry.upsertInstance(workflow, WorkflowRegistry.ExecutionStatus.Running)
    else registerNotRunningInstance(workflow)

  private def registerNotRunningInstance(workflow: ActiveWorkflow[F, ?]): F[Unit] = {
    val status =
      if workflow.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(workflow, status)
  }
}
