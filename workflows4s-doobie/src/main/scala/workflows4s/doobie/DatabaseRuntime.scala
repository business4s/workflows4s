package workflows4s.doobie

import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

import java.time.Clock

class DatabaseRuntime[Ctx <: WorkflowContext, WorkflowId](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    xa: Transactor[IO],
    storage: WorkflowStorage[WorkflowId, WCEvent[Ctx]],
    registry: WorkflowRegistry.Agent[WorkflowId],
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    IO {
      val base = new DbWorkflowInstance(
        id,
        ActiveWorkflow(workflow, initialState),
        storage,
        clock,
        knockerUpper,
        registry,
      )
      // alternative is to take `LiftIO` as runtime parameter but this complicates call site
      new MappedWorkflowInstance(
        base,
        [t] =>
          (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) =>
            WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.trans.apply(connIo.apply(liftIO))),
      )
    }

  }

}

object DatabaseRuntime {
  def default[Ctx <: WorkflowContext, WorkflowId](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[IO],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      storage: WorkflowStorage[WorkflowId, WCEvent[Ctx]],
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent[WorkflowId] = NoOpWorkflowRegistry.Agent,
  ) = new DatabaseRuntime[Ctx, WorkflowId](workflow, initialState, clock, knockerUpper, transactor, storage, registry)
}
