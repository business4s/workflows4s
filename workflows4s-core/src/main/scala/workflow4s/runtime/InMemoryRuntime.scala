package workflow4s.runtime

import java.time.Clock

import cats.effect.{Deferred, IO, Ref}
import workflow4s.runtime.wakeup.{KnockerUpper, SleepingKnockerUpper}
import workflow4s.wio.*
import workflow4s.wio.WIO.Initial

/** This runtime offers no persistance and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
class InMemoryRuntime[Ctx <: WorkflowContext, WorkflowId, Input](
    workflow: Initial[Ctx, Input],
    initialState: Input => WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Factory[(WorkflowId, IO[Unit])],
) extends WorkflowRuntime[IO, Ctx, WorkflowId, Input] {

  override def createInstance(id: WorkflowId, in: Input): IO[InMemoryWorkflowInstance[Ctx]] = {
    for {
      runningWfRef <- Deferred[IO, InMemoryWorkflowInstance[Ctx]]
      initialWf     = ActiveWorkflow(workflow.provideInput(in), initialState(in))(new Interpreter(knockerUpper(id, runningWfRef.get.flatMap(_.wakeup()))))
      stateRef     <- Ref[IO].of(initialWf)
      eventsRef    <- Ref[IO].of(Vector[WCEvent[Ctx]]())
      runningWf     = InMemoryWorkflowInstance[Ctx](stateRef, eventsRef, clock)
      _            <- runningWfRef.complete(runningWf)
    } yield runningWf
  }

}

object InMemoryRuntime {

  def default[Ctx <: WorkflowContext, In](workflow: Initial[Ctx, In], initialState: In => WCState[Ctx]): InMemoryRuntime[Ctx, Unit, In] =
    new InMemoryRuntime[Ctx, Unit, In](workflow, initialState, Clock.systemUTC(), SleepingKnockerUpper.factory.compose(_._2))

  def default[Ctx <: WorkflowContext, In <: WCState[Ctx]](workflow: Initial[Ctx, In]): InMemoryRuntime[Ctx, Unit, In] =
    default(workflow, identity)

}
