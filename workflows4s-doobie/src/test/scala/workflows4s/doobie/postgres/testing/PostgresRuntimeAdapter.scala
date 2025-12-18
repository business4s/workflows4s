package workflows4s.doobie.postgres.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.testing.ResultTestRuntimeAdapter
import workflows4s.doobie.{ByteCodec, DatabaseRuntime, Result}
import workflows4s.runtime.WorkflowInstance
import workflows4s.utils.StringUtils
import workflows4s.wio.*

type WorkflowId = String

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]]) extends ResultTestRuntimeAdapter[Ctx] {

  type Actor = WorkflowInstance[IO, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[Result, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val storage = PostgresWorkflowStorage()(using eventCodec)
    val runtime = DatabaseRuntime.create[Ctx](workflow, state, xa, resultEngine, storage, "test")
    val id      = StringUtils.randomAlphanumericString(12)

    runtime.createInstance(id).unsafeRunSync()
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
