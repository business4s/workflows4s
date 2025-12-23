package workflows4s.doobie.postgres.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.util.transactor.Transactor
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.utils.StringUtils
import workflows4s.wio.*

type WorkflowId = String

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {

  override type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val runtime  = DatabaseRuntime.create[Ctx](workflow, state, xa, engine, eventCodec, "test")
    val id       = StringUtils.randomAlphanumericString(12)
    val instance = runtime.createInstance(id).unsafeRunSync()
    // Wrap IO-based instance to Id for test compatibility
    MappedWorkflowInstance(instance, [t] => (x: IO[t]) => x.unsafeRunSync())
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
