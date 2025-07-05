package workflows4s.doobie.sqlite.testing

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.testing.TestRuntimeAdapter.Identifiable
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](workdir: Path, eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx, String] {

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
      registryAgent: WorkflowRegistry.Agent[String],
  ): Actor = {
    val id      = s"sqlruntime-workflow-${Random.nextLong()}"
    logger.debug(s"Creating instance $id")
    val runtime = SqliteRuntime.default[Ctx](workflow, state, eventCodec, knockerUpper, workdir, clock, registryAgent).unsafeRunSync()
    Actor(id, runtime.createInstance(id).unsafeRunSync())
  }

  override def recover(first: Actor): Actor = {
    first // in this runtime there is no in-memory state, hence no recovery.
  }

  case class Actor(id: String, base: WorkflowInstance[IO, WCState[Ctx]]) extends WorkflowInstance[Id, WCState[Ctx]] with Identifiable[String] {

    override def queryState(): WCState[Ctx] = base.queryState().unsafeRunSync()

    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[WorkflowInstance.UnexpectedSignal, Resp] =
      base.deliverSignal(signalDef, req).unsafeRunSync()

    override def wakeup(): Id[Unit] = base.wakeup().unsafeRunSync()

    override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]] = base.getProgress.unsafeRunSync()
  }

}
