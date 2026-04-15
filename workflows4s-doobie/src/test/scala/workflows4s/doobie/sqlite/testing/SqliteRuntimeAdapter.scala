package workflows4s.doobie.sqlite.testing

import cats.Id
import cats.effect.Async
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance}
import workflows4s.testing.{RecordingKnockerUpper, TestClock, TestRuntimeAdapter}
import workflows4s.wio.*

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[F[_]: Async, Ctx <: WorkflowContext](
    workdir: Path,
    eventCodec: ByteCodec[WCEvent[Ctx]],
    runSyncFn: [A] => F[A] => A,
)(using ev: LiftWorkflowEffect[Ctx, F])
    extends TestRuntimeAdapter[F, Ctx] {

  override protected val knockerUpper: RecordingKnockerUpper[F] = RecordingKnockerUpper[F]()
  override val clock: TestClock                                 = TestClock()
  override val registry: InMemoryWorkflowRegistry[F]            = InMemoryWorkflowRegistry[F](clock)
  override val engine: WorkflowInstanceEngine[F, Ctx]           =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  override def runSync[A](fa: F[A]): A                          = runSyncFn(fa)

  type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val id      = s"sqlruntime-workflow-${Random.nextLong()}"
    val runtime = runSyncFn(SqliteRuntime.create(workflow, state, eventCodec, engine, workdir))
    MappedWorkflowInstance(runSyncFn(runtime.createInstance(id)), [t] => (x: F[t]) => runSyncFn(x))
  }

  override def recover(first: Actor): Actor = first // in this runtime there is no in-memory state, hence no recovery.

}
