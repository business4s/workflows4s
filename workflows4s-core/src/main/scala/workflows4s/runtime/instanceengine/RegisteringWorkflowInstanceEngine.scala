package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

// TODO check how this engine interacts with non-greedy mode.
//  What happens when there is more to execute? What is unexpected signall appers between wakeups?
class RegisteringWorkflowInstanceEngine[F[_]](protected val delegate: WorkflowInstanceEngine[F], registry: WorkflowRegistry.Agent[F])(using
    val E: Effect[F],
) extends DelegatingWorkflowInstanceEngine[F] {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] = {
    E.flatMap(super.triggerWakeup(workflow)) { prevResult =>
      E.map(registeringRunningInstance(workflow, prevResult.hasEffect))(_ => prevResult)
    }
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = {
    E.flatMap(super.handleSignal(workflow, signalDef, req)) { prevResult =>
      E.map(registeringRunningInstance(workflow, prevResult.hasEffect))(_ => prevResult)
    }
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    E.productL(super.onStateChange(oldState, newState), registerNotRunningInstance(newState))
  }

  private def registeringRunningInstance(workflow: ActiveWorkflow[?], hasFollowup: Boolean): F[Unit] =
    if hasFollowup then registry.upsertInstance(workflow, WorkflowRegistry.ExecutionStatus.Running)
    else registerNotRunningInstance(workflow)

  private def registerNotRunningInstance(workflow: ActiveWorkflow[?]): F[Unit] = {
    val status =
      if workflow.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(workflow, status)
  }
}
