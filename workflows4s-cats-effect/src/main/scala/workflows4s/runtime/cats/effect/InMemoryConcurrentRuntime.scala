package workflows4s.runtime.cats.effect

import _root_.cats.effect.std.{AtomicCell, Semaphore}
import _root_.cats.effect.{Async, Ref}
import _root_.cats.syntax.all.*
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.util.UUID

/** In-memory runtime using functional concurrency primitives (Ref, AtomicCell, Semaphore). Fiber-safe and cancellation-safe.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryConcurrentRuntime[F[+_]: Async, Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F, Ctx],
    instances: Ref[F, Map[String, InMemoryConcurrentWorkflowInstance[F, Ctx]]],
    val templateId: String,
) extends WorkflowRuntime[F, Ctx] {
  def createInstance(id: String): F[InMemoryConcurrentWorkflowInstance[F, Ctx]] = {
    instances.access.flatMap({ (map, update) =>
      map.get(id) match {
        case Some(instance) => instance.pure[F]
        case None           =>
          for {
            instanceId = WorkflowInstanceId(templateId, id)
            initialWf  = ActiveWorkflow(instanceId, workflow, initialState)
            stateRef  <- AtomicCell[F].of(initialWf)
            eventsRef <- Ref[F].of(Vector[WCEvent[Ctx]]())
            lock      <- Semaphore[F](1)
            runningWf  = InMemoryConcurrentWorkflowInstance[F, Ctx](instanceId, stateRef, eventsRef, engine, lock)
            success   <- update(map.updated(id, runningWf))
            result    <- if success then runningWf.pure[F]
                         else createInstance(id) // retry on concurrent modification
          } yield result
      }
    })
  }
}

object InMemoryConcurrentRuntime {

  def default[F[+_]: Async, Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[F, Ctx],
      templateId: String = s"in-memory-concurrent-${UUID.randomUUID().toString.take(8)}",
  ): F[InMemoryConcurrentRuntime[F, Ctx]] = {
    Ref
      .of[F, Map[String, InMemoryConcurrentWorkflowInstance[F, Ctx]]](Map.empty)
      .map({ instances =>
        new InMemoryConcurrentRuntime[F, Ctx](workflow, initialState, engine, instances, templateId)
      })
  }

}
