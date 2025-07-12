package workflows4s.doobie.postgres.testing

import cats.Id
import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.utils.StringUtils
import workflows4s.wio.*

type WorkflowId = String

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {

  type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
      registryAgent: WorkflowRegistry.Agent,
  ): Actor = {
    val storage = PostgresWorkflowStorage()(using eventCodec)
    val runtime = DatabaseRuntime.default[Ctx](workflow, state, xa, knockerUpper, storage, "test", clock, registryAgent)
    val id      = StringUtils.randomAlphanumericString(12)
    import cats.effect.unsafe.implicits.global

    MappedWorkflowInstance(runtime.createInstance(id).unsafeRunSync(), [t] => (x: IO[t]) => x.unsafeRunSync())
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
