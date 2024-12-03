package workflows4s.doobie.postgres

import cats.effect.IO
import doobie.{ConnectionIO, WeakAsync}
import workflow4s.runtime.RunningWorkflow
import workflow4s.wio.{KnockerUpper, WCEvent, WCState, WIO, WorkflowContext}
import workflows4s.doobie.{DbWorkflowInstance, EventCodec}

import java.time.Clock

class PostgresRuntime(knockerUpper: WorkflowId => KnockerUpper, clock: Clock = Clock.systemUTC()) {

  def runWorkflow[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      id: WorkflowId,
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      initialState: In,
      eventCodec: EventCodec[WCEvent[Ctx]],
  ): IO[RunningWorkflow[ConnectionIO, WCState[Ctx]]] = runWorkflowWithState[Ctx, In](id, workflow, initialState, initialState, eventCodec)

  def runWorkflowWithState[Ctx <: WorkflowContext, In](
      id: WorkflowId,
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
  ): IO[RunningWorkflow[ConnectionIO, WCState[Ctx]]] = {
    WeakAsync
      .liftIO[ConnectionIO]
      .use(liftIo =>
        IO(
          new DbWorkflowInstance(
            id,
            workflow.provideInput(input),
            initialState,
            PostgresWorkflowStorage,
            liftIo,
            eventCodec,
            knockerUpper(id),
            clock
          ),
        ),
      )
  }

}
