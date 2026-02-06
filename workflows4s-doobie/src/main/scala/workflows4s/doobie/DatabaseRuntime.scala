package workflows4s.doobie

import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

/** Runtime backed by a shared database (e.g. PostgreSQL) via Doobie. Events are persisted per instance,
  * and concurrent access is guarded by [[WorkflowStorage.lockWorkflow]].
  */
class DatabaseRuntime[Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine,
    xa: Transactor[IO],
    storage: WorkflowStorage[WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[IO, Ctx] {

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val instanceId = WorkflowInstanceId(templateId, id)
    val base       = new DbWorkflowInstance(
      instanceId,
      ActiveWorkflow(instanceId, workflow, initialState),
      storage,
      engine,
    )
    // alternative is to take `LiftIO` as runtime parameter but this complicates call site
    val mapped     = new MappedWorkflowInstance(
      base,
      [t] =>
        (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) => WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.trans.apply(connIo.apply(liftIO))),
    )
    IO.pure(mapped)
  }
}

object DatabaseRuntime {
  // TODO seems redundant, to be removed if its still the case after few months
  def create[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[IO],
      engine: WorkflowInstanceEngine,
      storage: WorkflowStorage[WCEvent[Ctx]],
      templateId: String, // this has to be explicit, as it will be saved in the database and has to be consistent across runtimes
  ): DatabaseRuntime[Ctx] = {
    new DatabaseRuntime[Ctx](workflow, initialState, engine, transactor, storage, templateId)
  }
}
