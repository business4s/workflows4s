package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.kernel.Resource
import doobie.ConnectionIO
import doobie.util.transactor.Transactor
import workflows4s.doobie.{DbWorkflowInstance, EventCodec}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

import java.time.Clock

class PostgresRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    eventCodec: EventCodec[WCEvent[Ctx]],
    xa: Transactor[IO],
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    IO {
      val base = new DbWorkflowInstance(
        id,
        ActiveWorkflow(workflow, initialState),
        PostgresWorkflowStorage,
        eventCodec,
        clock,
        knockerUpper,
      )
      new MappedWorkflowInstance(base, [t] => (resource: Resource[IO, ConnectionIO[t]]) => resource.use(connIo => xa.trans.apply(connIo)))
    }

  }

}

object PostgresRuntime {
  def default[Ctx <: WorkflowContext, Input](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      xa: Transactor[IO],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      clock: Clock = Clock.systemUTC(),
  ) = new PostgresRuntime[Ctx](workflow, initialState, clock, knockerUpper, eventCodec, xa)
}
