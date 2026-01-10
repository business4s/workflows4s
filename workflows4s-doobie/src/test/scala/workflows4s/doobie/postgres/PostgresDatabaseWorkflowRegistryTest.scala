package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.ConnectionIO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.TestClock
import doobie.implicits.given
import org.scalatest.BeforeAndAfterEach
import workflows4s.doobie.postgres.testing.PostgresSuite
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.utils.StringUtils
import workflows4s.wio.{ActiveWorkflow, WIO, WorkflowContext}

import scala.concurrent.duration.DurationInt

class PostgresDatabaseWorkflowRegistryTest extends AnyFreeSpec with PostgresSuite with Matchers with BeforeAndAfterEach {

  override def afterEach() = {
    super.afterEach()
    sql"TRUNCATE table executing_workflows".update.run.transact(xa).void.unsafeRunSync()
  }

  "PostgresDatabaseWorkflowRegistry" - {
    "should store and retrieve workflow instances" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      val List(id1, id2, id3, id4) =
        List.fill(4)(WorkflowInstanceId(StringUtils.randomAlphanumericString(4), StringUtils.randomAlphanumericString(4)))

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Awaiting).unsafeRunSync()
      registry.upsertInstance(dummyAW(id3), ExecutionStatus.Finished).unsafeRunSync()
      registry.upsertInstance(dummyAW(id4), ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(0.seconds).drainUnsafe

      assert(workflows === List(id1, id4))
    }

    "should consider the last recorded update time when filtering" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      val List(id1, id2) = List.fill(2)(WorkflowInstanceId(StringUtils.randomAlphanumericString(4), StringUtils.randomAlphanumericString(4)))

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()

      clock.advanceBy(2.second)
      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 1.seconds).drainUnsafe

      assert(workflows === List(id2))
    }

    "should consider the last recorded update status" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      val List(id1, id2, id3) = List.fill(3)(WorkflowInstanceId(StringUtils.randomAlphanumericString(4), StringUtils.randomAlphanumericString(4)))

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id3), ExecutionStatus.Running).unsafeRunSync()

      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Finished).unsafeRunSync()
      registry.upsertInstance(dummyAW(id3), ExecutionStatus.Awaiting).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 0.seconds).drainUnsafe

      assert(workflows === List(id1))
    }

    "should separate ids by type" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      val id1 = WorkflowInstanceId("a", "1")
      val id2 = WorkflowInstanceId("b", "1")

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 0.seconds).drainUnsafe
      assert(workflows === List(id1, id2))
    }
  }

  extension [T](v: fs2.Stream[ConnectionIO, T]) {
    def drainUnsafe: List[T] = v.compile.toList.transact(xa).unsafeRunSync()
  }

  def dummyAW(id: WorkflowInstanceId): ActiveWorkflow[IO, WorkflowContext { type State = Null }] = ActiveWorkflow(id, WIO.End(), null)

}
