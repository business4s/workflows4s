package workflow4s.runtime

import cats.effect.{Deferred, IO, Ref}
import workflow4s.wio.WIO.Initial
import workflow4s.wio.{ActiveWorkflow, Interpreter, KnockerUpper, WCEvent, WCState, WIO, WorkflowContext}

import java.time.Clock

/** This runtime offers no persistance and stores all the events in memory It's designed to be used in test or in very specific scenarios.
  *
  * IT'S NOT A GENERAL-PURPOSE RUNTIME
  */
object InMemoryRuntime {

  def runWorkflow[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      initialState: In,
      events: Seq[WCEvent[Ctx]] = Seq(),
      clock: Clock = Clock.systemUTC(),
  ): IO[InMemoryRunningWorkflow[Ctx]] = runWorkflowWithState[Ctx, In](workflow, initialState, initialState, events, clock)

  def runWorkflowWithState[Ctx <: WorkflowContext, In](
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      initialState: WCState[Ctx],
      events: Seq[WCEvent[Ctx]] = Seq(),
      clock: Clock = Clock.systemUTC(),
  ): IO[InMemoryRunningWorkflow[Ctx]] = {
    for {
      runningWfRef <- Deferred[IO, InMemoryRunningWorkflow[Ctx]]
      initialWf     =
        ActiveWorkflow(workflow.provideInput(input), initialState)(new Interpreter(SleepingKnockerUpper(runningWfRef.get.flatMap(_.wakeup()))))
      stateRef     <- Ref[IO].of(initialWf)
      eventsRef    <- Ref[IO].of(Vector[WCEvent[Ctx]]())
      runningWf     = InMemoryRunningWorkflow[Ctx](stateRef, eventsRef, clock)
      _            <- runningWfRef.complete(runningWf)
      _            <- runningWf.recover(events)
    } yield runningWf
  }
}
