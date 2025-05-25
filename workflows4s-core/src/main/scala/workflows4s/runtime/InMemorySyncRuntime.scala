package workflows4s.runtime

import java.time.Clock
import cats.Id
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.{KnockerUpper, NoOpKnockerUpper}
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

class InMemorySyncRuntime[Ctx <: WorkflowContext, WorkflowId](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpperAgent: KnockerUpper.Agent[WorkflowId],
    registryAgent: WorkflowRegistry.Agent[WorkflowId],
)(using IORuntime)
    extends WorkflowRuntime[Id, Ctx, WorkflowId] {
  val instances = new java.util.concurrent.ConcurrentHashMap[WorkflowId, InMemorySyncWorkflowInstance[Ctx]]()

  override def createInstance(id: WorkflowId): InMemorySyncWorkflowInstance[Ctx] = {
    instances.computeIfAbsent(
      id,
      { _ =>
        val activeWf: ActiveWorkflow[Ctx] = ActiveWorkflow(workflow, initialState)
        new InMemorySyncWorkflowInstance[Ctx](activeWf, clock, knockerUpperAgent.curried(id), registryAgent.curried(id))
      },
    )
  }
}

object InMemorySyncRuntime {
  def default[Ctx <: WorkflowContext, Id](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpperAgent: KnockerUpper.Agent[Id] = NoOpKnockerUpper.Agent,
      clock: Clock = Clock.systemUTC(),
      registryAgent: WorkflowRegistry.Agent[Id] = NoOpWorkflowRegistry.Agent,
  ): InMemorySyncRuntime[Ctx, Id] =
    new InMemorySyncRuntime[Ctx, Id](workflow, initialState, clock, knockerUpperAgent, registryAgent)(using IORuntime.global)
}
