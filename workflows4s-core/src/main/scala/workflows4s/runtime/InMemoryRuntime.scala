package workflows4s.runtime

import cats.effect.{Deferred, IO, Ref}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

import java.time.Clock

/** This runtime offers no persistence and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryRuntime[Ctx <: WorkflowContext, WorkflowId](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    instances: Ref[IO, Map[WorkflowId, InMemoryWorkflowInstance[Ctx]]],
    registry: WorkflowRegistry.Agent[WorkflowId],
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {
  override def createInstance(id: WorkflowId): IO[InMemoryWorkflowInstance[Ctx]] = {
    instances.access.flatMap({ (map, update) =>
      map.get(id) match {
        case Some(instance) => IO.pure(instance)
        case None           =>
          for {
            runningWfRef <- Deferred[IO, InMemoryWorkflowInstance[Ctx]]
            initialWf     = ActiveWorkflow(workflow, initialState)
            stateRef     <- Ref[IO].of(initialWf)
            eventsRef    <- Ref[IO].of(Vector[WCEvent[Ctx]]())
            runningWf     = InMemoryWorkflowInstance[Ctx](stateRef, eventsRef, clock, knockerUpper.curried(id), registry.curried(id))
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

  def default[Ctx <: WorkflowContext, Id](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpper: KnockerUpper.Agent[Id],
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent[Id] = NoOpWorkflowRegistry.Agent,
  ): IO[InMemoryRuntime[Ctx, Id]] = {
    Ref
      .of[IO, Map[Id, InMemoryWorkflowInstance[Ctx]]](Map.empty)
      .map({ instances =>
        new InMemoryRuntime[Ctx, Id](workflow, initialState, clock, knockerUpper, instances, registry)
      })
  }

}
