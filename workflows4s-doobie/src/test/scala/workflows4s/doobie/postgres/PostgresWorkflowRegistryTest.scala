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
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.{WorkflowRegistry, WorkflowSearch}
import workflows4s.utils.StringUtils
import workflows4s.wio.{ActiveWorkflow, WIO, WorkflowContext}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

class PostgresWorkflowRegistryTest extends AnyFreeSpec with PostgresSuite with Matchers with BeforeAndAfterEach {

  override def afterEach() = {
    super.afterEach()
    sql"TRUNCATE table workflow_registry".update.run.transact(xa).void.unsafeRunSync()
  }

  "PostgresWorkflowRegistry" - {

    "getExecutingWorkflows" - {
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

        assert(workflows.toSet === Set(id1, id4))
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

    "knocker upper" - {
      "should dispatch due wakeups via poller" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, pollInterval = 100.millis, clock = clock).unsafeRunSync()

        val id         = WorkflowInstanceId("tpl", "inst-1")
        val dispatched = new AtomicReference[List[WorkflowInstanceId]](Nil)
        val callback   = (w: WorkflowInstanceId) => IO(dispatched.updateAndGet(w :: _)).void

        registry.updateWakeup(id, Some(clock.instant.minusSeconds(1))).unsafeRunSync()
        registry
          .initialize(callback)
          .use(_ => IO.sleep(500.millis))
          .unsafeRunSync()

        assert(dispatched.get() === List(id))
      }

      "should clear wakeup after dispatch" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, pollInterval = 100.millis, clock = clock).unsafeRunSync()

        val id = WorkflowInstanceId("tpl", "inst-2")
        registry.updateWakeup(id, Some(clock.instant.minusSeconds(1))).unsafeRunSync()
        registry
          .initialize(_ => IO.unit)
          .use(_ => IO.sleep(500.millis))
          .unsafeRunSync()

        val remaining = sql"SELECT COUNT(*) FROM workflow_registry WHERE wakeup_at IS NOT NULL"
          .query[Int]
          .unique
          .transact(xa)
          .unsafeRunSync()
        assert(remaining === 0)
      }

      "updateWakeup(None) should not resurrect a deleted row" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

        val id = WorkflowInstanceId("tpl", "inst-3")
        registry.upsertInstance(dummyAW(id), ExecutionStatus.Running).unsafeRunSync()
        registry.upsertInstance(dummyAW(id), ExecutionStatus.Finished).unsafeRunSync()
        registry.updateWakeup(id, None).unsafeRunSync()

        val rows = sql"SELECT COUNT(*) FROM workflow_registry".query[Int].unique.transact(xa).unsafeRunSync()
        assert(rows === 0)
      }
    }

    "search" - {
      "filters by status" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock, keepFinished = true).unsafeRunSync()

        val id1 = WorkflowInstanceId("t", "a")
        val id2 = WorkflowInstanceId("t", "b")
        val id3 = WorkflowInstanceId("t", "c")
        registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
        registry.upsertInstance(dummyAW(id2), ExecutionStatus.Awaiting).unsafeRunSync()
        registry.upsertInstance(dummyAW(id3), ExecutionStatus.Finished).unsafeRunSync()

        val running = registry.search("t", WorkflowSearch.Query(status = Set(ExecutionStatus.Running))).unsafeRunSync()
        assert(running.map(_.id) === List(id1))

        val all = registry.search("t", WorkflowSearch.Query()).unsafeRunSync()
        assert(all.map(_.id).toSet === Set(id1, id2, id3))
      }

      "filters by templateId" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

        registry.upsertInstance(dummyAW(WorkflowInstanceId("t1", "a")), ExecutionStatus.Running).unsafeRunSync()
        registry.upsertInstance(dummyAW(WorkflowInstanceId("t2", "b")), ExecutionStatus.Running).unsafeRunSync()

        val res = registry.search("t1", WorkflowSearch.Query()).unsafeRunSync()
        assert(res.map(_.id.instanceId) === List("a"))
      }

      "filters by tags" in {
        val clock    = TestClock()
        val tagger   = new WorkflowRegistry.Tagger[Null] {
          override def getTags(id: WorkflowInstanceId, state: Null): Map[String, String] =
            Map("env" -> id.instanceId.take(3))
        }
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock, taggers = Map("t" -> tagger)).unsafeRunSync()

        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "prod-1")), ExecutionStatus.Running).unsafeRunSync()
        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "dev-1")), ExecutionStatus.Running).unsafeRunSync()

        val prod = registry
          .search("t", WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.Equals("env", "pro"))))
          .unsafeRunSync()
        assert(prod.map(_.id.instanceId) === List("prod-1"))
        assert(prod.head.tags === Map("env" -> "pro"))

        val hasEnv = registry
          .search("t", WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.HasKey("env"))))
          .unsafeRunSync()
        assert(hasEnv.map(_.id.instanceId).toSet === Set("prod-1", "dev-1"))
      }

      "supports limit, offset and sort" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "a")), ExecutionStatus.Running).unsafeRunSync()
        clock.advanceBy(1.second)
        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "b")), ExecutionStatus.Running).unsafeRunSync()
        clock.advanceBy(1.second)
        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "c")), ExecutionStatus.Running).unsafeRunSync()

        val res = registry
          .search("t", WorkflowSearch.Query(sort = Some(WorkflowSearch.SortBy.CreatedDesc), limit = Some(2), offset = Some(1)))
          .unsafeRunSync()
        assert(res.map(_.id.instanceId) === List("b", "a"))
      }

      "count matches search" in {
        val clock    = TestClock()
        val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "a")), ExecutionStatus.Running).unsafeRunSync()
        registry.upsertInstance(dummyAW(WorkflowInstanceId("t", "b")), ExecutionStatus.Awaiting).unsafeRunSync()

        val q       = WorkflowSearch.Query(status = Set(ExecutionStatus.Running))
        val n       = registry.count("t", q).unsafeRunSync()
        val results = registry.search("t", q).unsafeRunSync()
        assert(n === results.size)
        assert(n === 1)
      }
    }
  }

  extension [T](v: fs2.Stream[ConnectionIO, T]) {
    def drainUnsafe: List[T] = v.compile.toList.transact(xa).unsafeRunSync()
  }

  def dummyAW(id: WorkflowInstanceId): ActiveWorkflow[WorkflowContext { type State = Null }] = ActiveWorkflow(id, WIO.End(), null)

}
