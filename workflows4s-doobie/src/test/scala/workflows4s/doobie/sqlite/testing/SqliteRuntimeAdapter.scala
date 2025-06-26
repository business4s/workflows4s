package workflows4s.doobie.sqlite.testing

import cats.Id
import cats.effect.IO
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*

import java.nio.file.Path
import java.time.Clock
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](dbPath: Path, eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx, String] {

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
      clock: Clock,
      registryAgent: WorkflowRegistry.Agent[String],
  ): Actor = {
    val runtime =
      SqliteRuntime.default[Ctx](workflow, state, eventCodec, NoOpKnockerUpper.Agent, dbPath, clock)
    Actor(runtime.createInstance(s"workflow-${Random.nextLong()}"))
  }

  override def recover(first: Actor): Actor = {
    first // in this runtime there is no in-memory state, hence no recovery.
  }

  case class Actor(base: IO[WorkflowInstance[IO, WCState[Ctx]]]) extends WorkflowInstance[Id, WCState[Ctx]] {
    import cats.effect.unsafe.implicits.global

    override def queryState(): WCState[Ctx] = base.flatMap(_.queryState()).unsafeRunSync()

    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[WorkflowInstance.UnexpectedSignal, Resp] =
      base.flatMap(_.deliverSignal(signalDef, req)).unsafeRunSync()

    override def wakeup(): Id[Unit] = base.flatMap(_.wakeup()).unsafeRunSync()

    override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]] = base.flatMap(_.getProgress).unsafeRunSync()
  }

}
