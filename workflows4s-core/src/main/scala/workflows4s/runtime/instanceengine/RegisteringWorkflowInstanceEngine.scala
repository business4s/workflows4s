package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.{ActiveWorkflow, SignalDef, WCEvent, WorkflowContext}

import java.time.Instant

class RegisteringWorkflowInstanceEngine(protected val delegate: WorkflowInstanceEngine, registry: WorkflowRegistry.Agent)
    extends DelegatingWorkflowInstanceEngine {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[Option[IO[Either[Instant, WCEvent[Ctx]]]]] = {
    for {
      prevResult <- super.triggerWakeup(workflow)
      _          <- registeringRunningInstance(workflow, prevResult)
    } yield prevResult
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[Option[IO[(WCEvent[Ctx], Resp)]]] = {
    for {
      prevResult <- super.handleSignal(workflow, signalDef, req)
      _          <- registeringRunningInstance(workflow, prevResult)
    } yield prevResult
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <* registerNotRunningInstance(newState)
  }

  private def registeringRunningInstance(workflow: ActiveWorkflow[?], nextStep: Option[IO[Any]]) =
    nextStep match {
      case Some(_) => registry.upsertInstance(workflow.id, WorkflowRegistry.ExecutionStatus.Running)
      case None    => registerNotRunningInstance(workflow)
    }

  private def registerNotRunningInstance(workflow: ActiveWorkflow[?]): IO[Unit] = {
    val status =
      if workflow.wio.asExecuted.isDefined then ExecutionStatus.Finished
      else ExecutionStatus.Awaiting
    registry.upsertInstance(workflow.id, status)
  }
}
