package workflows4s.doobie.sqlite.testing

import cats.effect.IO
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.runtime.{DelegateWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.{EventIntrospection, WorkflowTestAdapter}
import workflows4s.wio.*
import workflows4s.cats.CatsEffect.ioEffect

import java.nio.file.Path
import scala.util.Random

class SqliteRuntimeAdapter[Ctx <: WorkflowContext](
    workdir: Path,
    eventCodec: ByteCodec[WCEvent[Ctx]],
) extends WorkflowTestAdapter[IO, Ctx] {

  // satisfy the WorkflowTestAdapter requirements for IO
  implicit override val effect: Effect[IO] = ioEffect

  /** Wrapper for the SQLite-backed instance */
  case class SqliteTestActor(
      delegate: WorkflowInstance[IO, WCState[Ctx]],
      override val id: WorkflowInstanceId,
  ) extends DelegateWorkflowInstance[IO, WCState[Ctx]]
      with EventIntrospection[WCEvent[Ctx]] {
    // Events are in the SQLite file; we leave this empty for basic state tests
    override def getEvents: Seq[WCEvent[Ctx]] = Nil

    override def getExpectedSignals = delegate.getExpectedSignals
  }

  override type Actor = SqliteTestActor

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val idString = s"sqlruntime-workflow-${Random.nextLong()}"

    val action = for {
      runtime  <- SqliteRuntime.create[Ctx](workflow, state, eventCodec, engine, workdir)
      instance <- runtime.createInstance(idString)
    } yield SqliteTestActor(instance, instance.id)

    effect.runSyncUnsafe(action)
  }

  override def recover(first: Actor): Actor = {
    // Database runtimes are effectively stateless in memory;
    // the "new" actor will automatically reload events from the SQLite file.
    first
  }
}
