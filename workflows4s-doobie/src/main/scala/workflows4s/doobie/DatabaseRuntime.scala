package workflows4s.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class DatabaseRuntime[Ctx <: WorkflowContext](
    override val workflow: Initial[IO, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[IO],
    storage: WorkflowStorage[IO, WCEvent[Ctx]],
    val templateId: String,
) extends WorkflowRuntime[IO, Ctx] {

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val instanceId = WorkflowInstanceId(templateId, id)
    val instance   = new DbWorkflowInstance(
      instanceId,
      ActiveWorkflow(instanceId, workflow, initialState),
      storage,
      engine,
    )
    IO.pure(instance)
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
    val storage = new postgres.PostgresWorkflowStorage[WCEvent[Ctx]](transactor, tableName)(using eventCodec)
    new DatabaseRuntime[Ctx](workflow, initialState, engine, storage, templateId)
  }
}
