package workflows4s.doobie.postgres

import cats.effect.IO
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, Interpreter, WCEvent, WCState, WIO, WorkflowContext}
import workflows4s.doobie.{DbWorkflowInstance, EventCodec}

import java.time.Clock

class PostgresRuntime[Ctx <: WorkflowContext, Input](
    workflow: Initial[Ctx, Input],
    initialState: Input => WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Factory[WorkflowId],
    eventCodec: EventCodec[WCEvent[Ctx]],
    xa: Transactor[IO],
) extends WorkflowRuntime[IO, Ctx, WorkflowId, Input] {

  override def createInstance(id: WorkflowId, in: Input): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    WeakAsync
      .liftIO[ConnectionIO]
      .use(liftIo =>
        IO {
          val base = new DbWorkflowInstance(
            id,
            ActiveWorkflow(workflow.provideInput(in), initialState(in))(new Interpreter(knockerUpper(id))),
            PostgresWorkflowStorage,
            liftIo,
            eventCodec,
            clock,
          )
          new MappedWorkflowInstance(base, xa.trans)
        },
      )

  }

}

object PostgresRuntime {
  def defaultWithState[Ctx <: WorkflowContext, Input](
      workflow: Initial[Ctx, Input],
      initialState: Input => WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      xa: Transactor[IO],
      knockerUpper: KnockerUpper.Factory[WorkflowId],
      clock: Clock = Clock.systemUTC(),
  ) = new PostgresRuntime[Ctx, Input](workflow, initialState, clock, knockerUpper, eventCodec, xa)

  def default[Ctx <: WorkflowContext, Input <: WCState[Ctx]](
      workflow: Initial[Ctx, Input],
      eventCodec: EventCodec[WCEvent[Ctx]],
      xa: Transactor[IO],
      knockerUpper: KnockerUpper.Factory[WorkflowId],
      clock: Clock = Clock.systemUTC(),
  ) = defaultWithState(workflow, identity, eventCodec, xa, knockerUpper, clock)
}
