package workflows4s.runtime

import cats.MonadThrow
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

class InMemorySynchronizedRuntime[F[+_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
    val workflow: Initial[F, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F],
    val templateId: String,
) extends WorkflowRuntime[F, Ctx] {
  override type WorkflowEffect[A] = F[A]
  private val instances = new java.util.concurrent.ConcurrentHashMap[String, InMemorySynchronizedWorkflowInstance[F, Ctx]]()

  override def createInstance(id: String): F[InMemorySynchronizedWorkflowInstance[F, Ctx]] = {
    WeakSync[F].delay {
      instances.computeIfAbsent(
        id,
        { _ =>
          val instanceId = WorkflowInstanceId(templateId, id)
          val activeWf   = ActiveWorkflow(instanceId, workflow, initialState)
          new InMemorySynchronizedWorkflowInstance[F, Ctx](instanceId, activeWf, engine)
        },
      )
    }
  }
}

object InMemorySynchronizedRuntime {
  def create[F[+_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
      workflow: Initial[F, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[F],
      templateId: String = s"in-memory-synchronized-${java.util.UUID.randomUUID().toString.take(8)}",
  ): InMemorySynchronizedRuntime[F, Ctx] =
    new InMemorySynchronizedRuntime[F, Ctx](workflow, initialState, engine, templateId)
}
