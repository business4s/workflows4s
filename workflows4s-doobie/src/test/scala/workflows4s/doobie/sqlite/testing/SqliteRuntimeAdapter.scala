package workflows4s.doobie.sqlite.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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

  // Store workflow and state for recovery
  private var lastWorkflow: WIO.Initial[IO, Ctx] = scala.compiletime.uninitialized
  private var lastState: WCState[Ctx]            = scala.compiletime.uninitialized

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
    // Store for recovery
    lastWorkflow = workflow
    lastState = state

    val idString = s"sqlruntime-workflow-${Random.nextLong()}"

    val action = for {
      runtime  <- SqliteRuntime.create[Ctx](workflow, state, eventCodec, engine, workdir)
      instance <- runtime.createInstance(idString)
    } yield SqliteTestActor(instance, instance.id)

    effect.runSyncUnsafe(action)
  }

  override def recover(first: Actor): Actor = {
    // Create a fresh runtime and instance with the same ID
    // The new instance will replay events from the SQLite file to recover state
    val action = for {
      runtime  <- SqliteRuntime.create[Ctx](lastWorkflow, lastState, eventCodec, engine, workdir)
      instance <- runtime.createInstance(first.id.instanceId)
    } yield SqliteTestActor(instance, first.id)

    effect.runSyncUnsafe(action)
  }
}
