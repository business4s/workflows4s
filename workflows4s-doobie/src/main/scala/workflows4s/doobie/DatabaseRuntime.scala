package workflows4s.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import doobie.WeakAsync
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class DatabaseRuntime[Ctx <: WorkflowContext](
    override val workflow: Initial[IO, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[IO],
    xa: Transactor[IO],
    storage: WorkflowStorage[WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[IO, Ctx] {

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val instanceId                                 = WorkflowInstanceId(templateId, id)
    val base                                       = new DbWorkflowInstance(
      instanceId,
      ActiveWorkflow(instanceId, workflow, initialState),
      storage,
      engine,
    )
    // Convert DoobieEffect ~> IO by running the Kleisli with a LiftIO instance and transacting
    val mapped: WorkflowInstance[IO, WCState[Ctx]] = new MappedWorkflowInstance[DoobieEffect, IO, WCState[Ctx]](
      base,
      [t] => (doobieEffect: DoobieEffect[t]) => WeakAsync.liftIO[doobie.ConnectionIO].use(liftIO => xa.trans.apply(doobieEffect.run(liftIO))),
    )
    IO.pure(mapped)
  }
}

object DatabaseRuntime {
  def create[Ctx <: WorkflowContext](
      workflow: Initial[IO, Ctx],
      initialState: WCState[Ctx],
      transactor: Transactor[IO],
      engine: WorkflowInstanceEngine[IO],
      eventCodec: ByteCodec[WCEvent[Ctx]],
      templateId: String,
      tableName: String = "workflow_journal",
  ): DatabaseRuntime[Ctx] = {
    val storage = new postgres.PostgresWorkflowStorage[WCEvent[Ctx]](tableName)(using eventCodec)
    new DatabaseRuntime[Ctx](workflow, initialState, engine, transactor, storage, templateId)
  }
}
