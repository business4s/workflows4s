package workflows4s.runtime

import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory runtime that works with any effect type F[_].
  *
  * This runtime stores all events in memory and offers no persistence. It's designed for testing or specific scenarios where persistence is not
  * required.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME FOR PRODUCTION USE
  */
class InMemoryRuntime[F[_], Ctx <: WorkflowContext](
    val workflow: Initial[F, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F],
    val templateId: String,
)(using E: Effect[F])
    extends WorkflowRuntime[F, Ctx] {

  private val instances = new ConcurrentHashMap[String, InMemoryWorkflowInstance[F, Ctx]]()

  override def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]] = {
    E.delay {
      instances.computeIfAbsent(
        id,
        { _ =>
          val instanceId = WorkflowInstanceId(templateId, id)
          val activeWf   = ActiveWorkflow(instanceId, workflow, initialState)
          new InMemoryWorkflowInstance[F, Ctx](instanceId, activeWf, engine)
        },
      )
    }
  }
}

object InMemoryRuntime {

  def create[F[_]: Effect, Ctx <: WorkflowContext](
      workflow: Initial[F, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[F],
      templateId: String = s"in-memory-runtime-${UUID.randomUUID().toString.take(8)}",
  ): InMemoryRuntime[F, Ctx] =
    new InMemoryRuntime[F, Ctx](workflow, initialState, engine, templateId)
}
