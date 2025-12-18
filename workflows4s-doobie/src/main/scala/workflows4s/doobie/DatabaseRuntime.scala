package workflows4s.doobie

import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class DatabaseRuntime[Ctx <: WorkflowContext](
    private val internalWorkflow: Initial[Result, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[Result],
    xa: Transactor[IO],
    storage: WorkflowStorage[WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[IO, Ctx] {

  // For rendering purposes (diagrams), effect type doesn't matter - only structure
  override def workflow: Initial[IO, Ctx] = internalWorkflow.asInstanceOf[Initial[IO, Ctx]]

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val instanceId = WorkflowInstanceId(templateId, id)
    val base       = new DbWorkflowInstance(
      instanceId,
      ActiveWorkflow(instanceId, internalWorkflow, initialState),
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
      workflow: Initial[Result, Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[IO],
      engine: WorkflowInstanceEngine[Result],
      storage: WorkflowStorage[WCEvent[Ctx]],
      templateId: String, // this has to be explicit, as it will be saved in the database and has to be consistent across runtimes
  ): DatabaseRuntime[Ctx] = {
    new DatabaseRuntime[Ctx](workflow, initialState, engine, transactor, storage, templateId)
  }
}
