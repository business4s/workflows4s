package workflows4s.runtime

import workflows4s.runtime.instanceengine.{Effect, Ref, WorkflowInstanceEngine}
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.util.UUID

/** In-memory runtime that works with any effect type F[_].
  *
  * This runtime stores all events in memory and offers no persistence. It's designed for testing or specific scenarios where persistence is not
  * required.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME FOR PRODUCTION USE
  */
class InMemoryRuntime[F[_], Ctx <: WorkflowContext] private (
    val workflow: Initial[F, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F],
    val templateId: String,
    instances: Ref[F, Map[String, InMemoryWorkflowInstance[F, Ctx]]],
)(using E: Effect[F])
    extends WorkflowRuntime[F, Ctx] {

  override def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]] = {
    createInMemoryInstance(id).map(identity)
  }

  /** Create an instance with the concrete InMemoryWorkflowInstance type exposed. Useful for tests that need access to implementation-specific methods
    * like getEvents.
    */
  def createInMemoryInstance(id: String): F[InMemoryWorkflowInstance[F, Ctx]] = {
    instances
      .modify { currentMap =>
        currentMap.get(id) match {
          case Some(existing) => (currentMap, Some(existing)) // No change
          case None           => (currentMap, None)           // No change yet, signal creation needed
        }
      }
      .flatMap {
        case Some(existing) => E.pure(existing)
        case None           => createNewInstanceAtomic(id)
      }
  }

  private def createNewInstanceAtomic(id: String): F[InMemoryWorkflowInstance[F, Ctx]] = {
    val instanceId = WorkflowInstanceId(templateId, id)
    val activeWf   = ActiveWorkflow(instanceId, workflow, initialState)

    InMemoryWorkflowInstance.create[F, Ctx](instanceId, activeWf, engine).flatMap { newInstance =>
      instances.modify { currentMap =>
        currentMap.get(id) match {
          case Some(winner) => (currentMap, winner)
          case None         => (currentMap.updated(id, newInstance), newInstance)
        }
      }
    }
  }
}

object InMemoryRuntime {

  def create[F[_]: Effect, Ctx <: WorkflowContext](
      workflow: Initial[F, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[F],
      templateId: String = s"in-memory-runtime-${UUID.randomUUID().toString.take(8)}",
  ): F[InMemoryRuntime[F, Ctx]] = {
    Effect[F].ref(Map.empty[String, InMemoryWorkflowInstance[F, Ctx]]).map { instancesRef =>
      new InMemoryRuntime[F, Ctx](workflow, initialState, engine, templateId, instancesRef)
    }
  }
}
