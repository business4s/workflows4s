package workflows4s.runtime

import cats.effect.std.{AtomicCell, Semaphore}
import cats.effect.{Deferred, IO, Ref}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.util.UUID

/** This runtime offers no persistence and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryRuntime[Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[IO],
    instances: Ref[IO, Map[String, InMemoryWorkflowInstance[Ctx]]],
    val templateId: String,
) extends WorkflowRuntime[IO, Ctx] {
  override def createInstance(id: String): IO[InMemoryWorkflowInstance[Ctx]] = {
    instances.access.flatMap({ (map, update) =>
      map.get(id) match {
        case Some(instance) => IO.pure(instance)
        case None           =>
          for {
            runningWfRef <- Deferred[IO, InMemoryWorkflowInstance[Ctx]]
            instanceId    = WorkflowInstanceId(templateId, id)
            initialWf     = ActiveWorkflow(instanceId, workflow, initialState)
            stateRef     <- AtomicCell[IO].of(initialWf)
            eventsRef    <- Ref[IO].of(Vector[WCEvent[Ctx]]())
            lock         <- Semaphore[IO](1)
            runningWf     = InMemoryWorkflowInstance[Ctx](instanceId, stateRef, eventsRef, engine, lock)
            _            <- runningWfRef.complete(runningWf)
            success      <- update(map.updated(id, runningWf))
            _            <- if success then IO.unit
                            else IO.raiseError(new RuntimeException("Could not add workflow to active instances"))
          } yield runningWf
      }
    })
  }
}

object InMemoryRuntime {

  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[IO],
      templateId: String = s"in-memory-runtime-${UUID.randomUUID().toString.take(8)}",
  ): IO[InMemoryRuntime[Ctx]] = {
    Ref
      .of[IO, Map[String, InMemoryWorkflowInstance[Ctx]]](Map.empty)
      .map({ instances =>
        new InMemoryRuntime[Ctx](workflow, initialState, engine, instances, templateId)
      })
  }

}
