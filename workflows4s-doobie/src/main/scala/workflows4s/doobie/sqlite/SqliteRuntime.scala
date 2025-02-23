package workflows4s.doobie.sqlite

import java.time.Clock

import cats.effect.IO
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{DbWorkflowInstance, EventCodec}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class SqliteRuntime[WorkflowId <: String, Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    eventCodec: EventCodec[WCEvent[Ctx]],
    xa: Transactor[IO],
    // WorkflowId - should be path dependent and extracted from path
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    WeakAsync
      .liftIO[ConnectionIO]
      .use(liftIo =>
        IO {
          val base = new DbWorkflowInstance(
            id,
            ActiveWorkflow(workflow, initialState, None),
            SqliteWorkflowStorage,
            liftIo,
            eventCodec,
            clock,
            knockerUpper,
          )
          new MappedWorkflowInstance(base, xa.trans)
        },
      )
  }
}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext, WorkflowId <: String, Input](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      xa: Transactor[IO],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      clock: Clock = Clock.systemUTC(),
  ) =
    new SqliteRuntime(workflow = workflow, initialState = initialState, eventCodec = eventCodec, knockerUpper = knockerUpper, clock = clock, xa = xa)
}
