package workflows4s.runtime

import java.time.Clock
import cats.effect.{Deferred, IO, Ref}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.WIO.Initial

/** This runtime offers no persistance and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryRuntime[Ctx <: WorkflowContext, WorkflowId](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    registry: WorkflowRegistry.Agent[WorkflowId],
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): IO[InMemoryWorkflowInstance[Ctx]] = {
    for {
      runningWfRef <- Deferred[IO, InMemoryWorkflowInstance[Ctx]]
      initialWf     = ActiveWorkflow(workflow, initialState)
      stateRef     <- Ref[IO].of(initialWf)
      eventsRef    <- Ref[IO].of(Vector[WCEvent[Ctx]]())
      runningWf     = InMemoryWorkflowInstance[Ctx](stateRef, eventsRef, clock, knockerUpper.curried(id), registry.curried(id))
      _            <- runningWfRef.complete(runningWf)
    } yield runningWf
  }

}

object InMemoryRuntime {

  def default[Ctx <: WorkflowContext, Id](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpper: KnockerUpper.Agent[Id],
      registry: WorkflowRegistry.Agent[Id] = NoOpWorkflowRegistry.Agent,
  ): InMemoryRuntime[Ctx, Id] =
    new InMemoryRuntime[Ctx, Id](workflow, initialState, Clock.systemUTC(), knockerUpper, registry)

}
