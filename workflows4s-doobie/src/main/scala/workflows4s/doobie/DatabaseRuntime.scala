package workflows4s.doobie

import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

import java.time.Clock

class DatabaseRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent,
    xa: Transactor[IO],
    storage: WorkflowStorage[WCEvent[Ctx]],
    registry: WorkflowRegistry.Agent,
    val runtimeId: String,
) extends WorkflowRuntime[IO, Ctx] {

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val base   = new DbWorkflowInstance(
      WorkflowInstanceId(runtimeId, id),
      ActiveWorkflow(workflow, initialState),
      storage,
      clock,
      knockerUpper,
      registry,
    )
    // alternative is to take `LiftIO` as runtime parameter but this complicates call site
    val mapped = new MappedWorkflowInstance(
      base,
      [t] =>
        (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) => WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.trans.apply(connIo.apply(liftIO))),
    )
    IO.pure(mapped)
  }
}

object DatabaseRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[IO],
      knockerUpper: KnockerUpper.Agent,
      storage: WorkflowStorage[WCEvent[Ctx]],
      runtimeId: String, // this has to be explicit, as it will be saved in the database and has to be consistent across runtimes
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent = NoOpWorkflowRegistry.Agent,
  ) = {

    new DatabaseRuntime[Ctx](workflow, initialState, clock, knockerUpper, transactor, storage, registry, runtimeId)
  }
}
