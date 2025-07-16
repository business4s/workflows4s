package workflows4s.runtime

import java.time.Clock
import cats.Id
import cats.effect.unsafe.IORuntime
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.{KnockerUpper, NoOpKnockerUpper}
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

class InMemorySyncRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpperAgent: KnockerUpper.Agent,
    registryAgent: WorkflowRegistry.Agent,
    val templateId: String,
)(using IORuntime)
    extends WorkflowRuntime[Id, Ctx] {
  val instances = new java.util.concurrent.ConcurrentHashMap[String, InMemorySyncWorkflowInstance[Ctx]]()

  override def createInstance(id: String): InMemorySyncWorkflowInstance[Ctx] = {
    instances.computeIfAbsent(
      id,
      { _ =>
        val activeWf: ActiveWorkflow[Ctx] = ActiveWorkflow(workflow, initialState)
        val instanceId                    = WorkflowInstanceId(templateId, id)
        new InMemorySyncWorkflowInstance[Ctx](instanceId, activeWf, clock, knockerUpperAgent, registryAgent)
      },
    )
  }
}

object InMemorySyncRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpperAgent: KnockerUpper.Agent = NoOpKnockerUpper.Agent,
      clock: Clock = Clock.systemUTC(),
      registryAgent: WorkflowRegistry.Agent = NoOpWorkflowRegistry.Agent,
      templateId: String = s"in-memory-sync-runtime-${java.util.UUID.randomUUID().toString.take(8)}",
  ): InMemorySyncRuntime[Ctx] =
    new InMemorySyncRuntime[Ctx](workflow, initialState, clock, knockerUpperAgent, registryAgent, templateId)(using IORuntime.global)
}
