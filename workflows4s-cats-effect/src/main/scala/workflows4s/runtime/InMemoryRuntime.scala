package workflows4s.runtime

import cats.effect.std.{AtomicCell, Semaphore}
import cats.effect.{Async, Ref}
import cats.syntax.all.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.util.UUID

/** This runtime offers no persistence and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryRuntime[F[+_]: Async, Ctx <: WorkflowContext](
    val workflow: Initial[F, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F],
    instances: Ref[F, Map[String, InMemoryWorkflowInstance[F, Ctx]]],
    val templateId: String,
) extends WorkflowRuntime[F, Ctx] {
  override type WorkflowEffect[A] = F[A]

  def createInstance(id: String): F[InMemoryWorkflowInstance[F, Ctx]] = {
    instances.access.flatMap({ (map, update) =>
      map.get(id) match {
        case Some(instance) => instance.pure[F]
        case None           =>
          for {
            instanceId    = WorkflowInstanceId(templateId, id)
            initialWf     = ActiveWorkflow(instanceId, workflow, initialState)
            stateRef     <- AtomicCell[F].of(initialWf)
            eventsRef    <- Ref[F].of(Vector[WCEvent[Ctx]]())
            lock         <- Semaphore[F](1)
            runningWf     = InMemoryWorkflowInstance[F, Ctx](instanceId, stateRef, eventsRef, engine, lock)
            success      <- update(map.updated(id, runningWf))
            result       <- if success then runningWf.pure[F]
                            else createInstance(id) // retry on concurrent modification
          } yield result
      }
    })
  }
}

object InMemoryRuntime {

  def default[F[+_]: Async, Ctx <: WorkflowContext](
      workflow: Initial[F, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[F],
      templateId: String = s"in-memory-runtime-${UUID.randomUUID().toString.take(8)}",
  ): F[InMemoryRuntime[F, Ctx]] = {
    Ref
      .of[F, Map[String, InMemoryWorkflowInstance[F, Ctx]]](Map.empty)
      .map({ instances =>
        new InMemoryRuntime[F, Ctx](workflow, initialState, engine, instances, templateId)
      })
  }

}
