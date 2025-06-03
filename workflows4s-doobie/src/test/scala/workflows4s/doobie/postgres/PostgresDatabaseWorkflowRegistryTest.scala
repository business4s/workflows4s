package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie.ConnectionIO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.TestClock
import doobie.implicits.given
import org.scalatest.BeforeAndAfterEach
import workflows4s.doobie.postgres.testing.PostgresSuite

import scala.concurrent.duration.DurationInt
import scala.util.Random

class PostgresDatabaseWorkflowRegistryTest extends AnyFreeSpec with PostgresSuite with Matchers with BeforeAndAfterEach {

  override def afterEach() = {
    super.afterEach()
    sql"TRUNCATE table executing_workflows".update.run.transact(xa).void.unsafeRunSync()
  }

  "PostgresDatabaseWorkflowRegistry" - {
    "should store and retrieve workflow instances" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()
      val wfType1  = "test-workflow-1"
      val wfType2  = "test-workflow-2"

      val agent1 = registry.getAgent(wfType1)
      val agent2 = registry.getAgent(wfType2)

      val List(id1, id2, id3, id4) = List.fill(4)(WorkflowId(Random.nextLong()))

      agent1.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      agent1.upsertInstance(id2, ExecutionStatus.Awaiting).unsafeRunSync()
      agent2.upsertInstance(id3, ExecutionStatus.Finished).unsafeRunSync()
      agent2.upsertInstance(id4, ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(0.seconds).drainUnsafe

      assert(
        workflows === List(
          (wfType1, id1),
          (wfType2, id4),
        ),
      )
    }

    "should consider last recorded update time when filtering" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()
      val wfType   = "test-workflow-1"
      val agent    = registry.getAgent(wfType)

      val List(id1, id2) = List.fill(2)(WorkflowId(Random.nextLong()))

      agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      agent.upsertInstance(id2, ExecutionStatus.Running).unsafeRunSync()

      clock.advanceBy(2.second)
      agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 1.seconds).drainUnsafe

      assert(
        workflows === List(
          (wfType, id2),
        ),
      )
    }

    "should consider last recorded update status" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()
      val wfType   = "test-workflow-1"
      val agent    = registry.getAgent(wfType)

      val List(id1, id2, id3) = List.fill(3)(WorkflowId(Random.nextLong()))

      agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      agent.upsertInstance(id2, ExecutionStatus.Running).unsafeRunSync()
      agent.upsertInstance(id3, ExecutionStatus.Running).unsafeRunSync()

      agent.upsertInstance(id2, ExecutionStatus.Finished).unsafeRunSync()
      agent.upsertInstance(id3, ExecutionStatus.Awaiting).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 0.seconds).drainUnsafe

      assert(
        workflows === List(
          (wfType, id1),
        ),
      )
    }

    "should separate ids by type" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()
      val wfType1  = "test-workflow-1"
      val wfType2  = "test-workflow-2"
      val agent1   = registry.getAgent(wfType1)
      val agent2   = registry.getAgent(wfType2)

      val id = WorkflowId(1)

      agent1.upsertInstance(id, ExecutionStatus.Running).unsafeRunSync()
      agent2.upsertInstance(id, ExecutionStatus.Running).unsafeRunSync()

      val workflows = registry.getExecutingWorkflows(notUpdatedFor = 0.seconds).drainUnsafe
      assert(
        workflows === List(
          (wfType1, id),
          (wfType2, id),
        ),
      )
    }
  }

  extension [T](v: fs2.Stream[ConnectionIO, T]) {
    def drainUnsafe: List[T] = v.compile.toList.transact(xa).unsafeRunSync()
  }
}
