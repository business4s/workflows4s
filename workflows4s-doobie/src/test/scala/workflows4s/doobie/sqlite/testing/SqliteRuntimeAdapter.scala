package workflows4s.doobie.sqlite.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.{RecordingKnockerUpper, TestClock, TestRuntimeAdapter}
import workflows4s.wio.*
import workflows4s.wio.given

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](
    workdir: Path,
    eventCodec: ByteCodec[WCEvent[Ctx]],
)(using ev: LiftWorkflowEffect[Ctx, IO])
    extends TestRuntimeAdapter[IO, Ctx] {

  override protected val knockerUpper: RecordingKnockerUpper[IO] = RecordingKnockerUpper[IO]()
  override val clock: TestClock                                  = TestClock()
  override val registry: InMemoryWorkflowRegistry[IO]            = InMemoryWorkflowRegistry[IO](clock)
  override val engine: WorkflowInstanceEngine[IO, Ctx]           =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  override def runSync[A](fa: IO[A]): A                          = fa.unsafeRunSync()

  type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val id      = s"sqlruntime-workflow-${Random.nextLong()}"
    val runtime = SqliteRuntime.create[Ctx](workflow, state, eventCodec, engine, workdir).unsafeRunSync()
    MappedWorkflowInstance(runtime.createInstance(id).unsafeRunSync(), [t] => (x: IO[t]) => x.unsafeRunSync())
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
