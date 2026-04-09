package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.Async
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

/** Runtime backed by a shared database (e.g. PostgreSQL) via Doobie. Events are persisted per instance, and concurrent access is guarded by
  * [[WorkflowStorage.lockWorkflow]].
  */
class DatabaseRuntime[F[_]: Async, Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F, Ctx],
    xa: Transactor[F],
    storage: WorkflowStorage[WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[F, Ctx] {
  override def createInstance(id: String): F[WorkflowInstance[F, WCState[Ctx]]] = {
    val instanceId = WorkflowInstanceId(templateId, id)
    val base       = new DbWorkflowInstance[F, Ctx](
      instanceId,
      ActiveWorkflow(instanceId, workflow, initialState),
      storage,
      engine,
    )
    val mapped     = new MappedWorkflowInstance(
      base,
      [t] =>
        (connIo: Kleisli[ConnectionIO, FunctionK[F, ConnectionIO], t]) =>
          WeakAsync.liftK[F, ConnectionIO].use(fk => xa.trans.apply(connIo.apply(fk))),
    )
    Async[F].pure(mapped)
  }
}

object DatabaseRuntime {
  // TODO seems redundant, to be removed if its still the case after few months
  def create[F[_]: Async, Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[F],
      engine: WorkflowInstanceEngine[F, Ctx],
      storage: WorkflowStorage[WCEvent[Ctx]],
      templateId: String, // this has to be explicit, as it will be saved in the database and has to be consistent across runtimes
  ): DatabaseRuntime[F, Ctx] = {
    new DatabaseRuntime[F, Ctx](workflow, initialState, engine, transactor, storage, templateId)
  }
}
