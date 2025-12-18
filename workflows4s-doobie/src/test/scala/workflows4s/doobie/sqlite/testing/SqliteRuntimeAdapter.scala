package workflows4s.doobie.sqlite.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.doobie.testing.ResultTestRuntimeAdapter
import workflows4s.doobie.{ByteCodec, Result}
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.WorkflowInstance
import workflows4s.wio.*

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](workdir: Path, eventCodec: ByteCodec[WCEvent[Ctx]]) extends ResultTestRuntimeAdapter[Ctx] {

  type Actor = WorkflowInstance[IO, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[Result, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val id      = s"sqlruntime-workflow-${Random.nextLong()}"
    val runtime = SqliteRuntime.create[Ctx](workflow, state, eventCodec, resultEngine, workdir).unsafeRunSync()
    runtime.createInstance(id).unsafeRunSync()
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
