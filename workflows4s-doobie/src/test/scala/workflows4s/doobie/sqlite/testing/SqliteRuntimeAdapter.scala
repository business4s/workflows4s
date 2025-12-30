package workflows4s.doobie.sqlite.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.*

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](workdir: Path, eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {

  override type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val id       = s"sqlruntime-workflow-${Random.nextLong()}"
    val runtime  = SqliteRuntime.create[Ctx](workflow, state, eventCodec, engine, workdir).unsafeRunSync()
    val instance = runtime.createInstance(id).unsafeRunSync()
    // Wrap IO-based instance to Id for test compatibility
    MappedWorkflowInstance(instance, [t] => (x: IO[t]) => x.unsafeRunSync())
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
