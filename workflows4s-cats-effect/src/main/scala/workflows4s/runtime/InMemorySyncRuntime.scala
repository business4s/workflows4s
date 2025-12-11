package workflows4s.runtime

import cats.Id
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

class InMemorySyncRuntime[Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[Id],
    val templateId: String,
) extends WorkflowRuntime[Id, Ctx] {
  val instances = new java.util.concurrent.ConcurrentHashMap[String, InMemorySyncWorkflowInstance[Ctx]]()

  override def createInstance(id: String): InMemorySyncWorkflowInstance[Ctx] = {
    instances.computeIfAbsent(
      id,
      { _ =>
        val instanceId                    = WorkflowInstanceId(templateId, id)
        val activeWf: ActiveWorkflow[Ctx] = ActiveWorkflow(instanceId, workflow, initialState)
        new InMemorySyncWorkflowInstance[Ctx](instanceId, activeWf, engine)
      },
    )
  }
}

object InMemorySyncRuntime {
  def create[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[Id],
      templateId: String = s"in-memory-sync-runtime-${java.util.UUID.randomUUID().toString.take(8)}",
  ): InMemorySyncRuntime[Ctx] =
    new InMemorySyncRuntime[Ctx](workflow, initialState, engine, templateId)
}
