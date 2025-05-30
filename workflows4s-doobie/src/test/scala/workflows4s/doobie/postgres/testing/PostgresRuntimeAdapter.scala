package workflows4s.doobie.postgres.testing

import cats.Id
import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.{PostgresWorkflowStorage, WorkflowId}
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*

import java.time.Clock
import scala.util.Random

class PostgresRuntimeAdapter[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]])
    extends TestRuntimeAdapter[Ctx, WorkflowId] {

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
      clock: Clock,
      registryAgent: WorkflowRegistry.Agent[WorkflowId],
  ): Actor = {
    val storage = PostgresWorkflowStorage()(using eventCodec)
    val runtime =
      DatabaseRuntime.default[Ctx, WorkflowId](workflow, state, xa, NoOpKnockerUpper.Agent, storage, clock, registryAgent)
    Actor(runtime.createInstance(WorkflowId(Random.nextLong())))
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
