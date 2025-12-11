package workflows4s.doobie.postgres.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.util.transactor.Transactor
import workflows4s.catseffect.CatsEffect.given
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.runtime.instanceengine.{BasicJavaTimeEngine, GreedyWorkflowInstanceEngine, LoggingWorkflowInstanceEngine, WorkflowInstanceEngine}
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.utils.StringUtils
import workflows4s.wio.*

type WorkflowId = String

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {

  type Actor = WorkflowInstance[Id, WCState[Ctx]]

  // Create IO-based engine for the database runtime
  private val ioEngine: WorkflowInstanceEngine[IO] = {
    val base   = new BasicJavaTimeEngine[IO](clock)
    val greedy = GreedyWorkflowInstanceEngine[IO](base)
    new LoggingWorkflowInstanceEngine[IO](greedy)
  }

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val storage = PostgresWorkflowStorage()(using eventCodec)
    val runtime = DatabaseRuntime.create[Ctx](workflow, state, xa, ioEngine, storage, "test")
    val id      = StringUtils.randomAlphanumericString(12)

    MappedWorkflowInstance(runtime.createInstance(id).unsafeRunSync(), [t] => (x: IO[t]) => x.unsafeRunSync())
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
