package workflows4s.doobie.postgres

import cats.effect.unsafe.implicits.global
import doobie.ConnectionIO
import doobie.implicits.given
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.doobie.postgres.testing.PostgresSuite
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.{WorkflowRegistry, WorkflowSearch}
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.TestClock
import workflows4s.utils.StringUtils
import workflows4s.wio.{ActiveWorkflow, WIO, WorkflowContext}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

class PostgresDatabaseWorkflowRegistryTest extends AnyFreeSpec with PostgresSuite with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit = {
    super.afterEach()
    sql"TRUNCATE table executing_workflows".update.run.transact(xa).void.unsafeRunSync()
  }

  "PostgresDatabaseWorkflowRegistry" - {
    "should store and retrieve running workflow instances" in {
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

    "should search by templateId" in {
      val clock    = TestClock()
      val registry = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      val id1 = WorkflowInstanceId("template-a", "1")
      val id2 = WorkflowInstanceId("template-b", "2")

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()

      val results = registry.search("template-a", WorkflowSearch.Query()).unsafeRunSync()
      results.map(_.id) shouldBe List(id1)
    }

    "should search by status" in {
      val clock               = TestClock()
      val templateId          = "test"
      val registry            = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()
      val List(id1, id2, id3) = List("1", "2", "3").map(WorkflowInstanceId(templateId, _))

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Awaiting).unsafeRunSync()
      registry.upsertInstance(dummyAW(id3), ExecutionStatus.Finished).unsafeRunSync()

      val running = registry.search(templateId, WorkflowSearch.Query(status = Set(ExecutionStatus.Running))).unsafeRunSync()
      running.map(_.id) shouldBe List(id1)

      val notFinished =
        registry.search(templateId, WorkflowSearch.Query(status = Set(ExecutionStatus.Running, ExecutionStatus.Awaiting))).unsafeRunSync()
      notFinished.map(_.id) should contain theSameElementsAs List(id1, id2)
    }

    "should search by tags" in {
      val clock      = TestClock()
      val templateId = "test"
      val tagMap     = mutable.Map.empty[String, Map[String, String]]
      val tagger     = new WorkflowRegistry.Tagger[Null] {
        override def getTags(id: WorkflowInstanceId, state: Null): Map[String, String] = tagMap.getOrElse(id.instanceId, Map.empty)
      }
      val registry   = PostgresWorkflowRegistry(xa = xa, clock = clock, taggers = Map(templateId -> tagger)).unsafeRunSync()

      val List(id1, id2, id3) = List("1", "2", "3").map(WorkflowInstanceId(templateId, _))
      tagMap("1") = Map("env" -> "prod", "region" -> "us")
      tagMap("2") = Map("env" -> "staging")
      tagMap("3") = Map("env" -> "prod", "region" -> "eu")

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id3), ExecutionStatus.Running).unsafeRunSync()

      // Equals filter
      val prodResults =
        registry.search(templateId, WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.Equals("env", "prod")))).unsafeRunSync()
      prodResults.map(_.id) should contain theSameElementsAs List(id1, id3)

      // HasKey filter
      val hasRegion =
        registry.search(templateId, WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.HasKey("region")))).unsafeRunSync()
      hasRegion.map(_.id) should contain theSameElementsAs List(id1, id3)

      // In filter
      val inRegions =
        registry.search(templateId, WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.In("region", Set("us", "eu"))))).unsafeRunSync()
      inRegions.map(_.id) should contain theSameElementsAs List(id1, id3)

      // NotEquals filter
      val notStaging =
        registry.search(templateId, WorkflowSearch.Query(tagFilters = List(WorkflowSearch.TagFilter.NotEquals("env", "staging")))).unsafeRunSync()
      notStaging.map(_.id) should contain theSameElementsAs List(id1, id3)
    }

    "should count results" in {
      val clock      = TestClock()
      val templateId = "test"
      val registry   = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      List("1", "2", "3").map(WorkflowInstanceId(templateId, _)).foreach { id =>
        registry.upsertInstance(dummyAW(id), ExecutionStatus.Running).unsafeRunSync()
      }

      registry.count(templateId, WorkflowSearch.Query()).unsafeRunSync() shouldBe 3
      registry.count(templateId, WorkflowSearch.Query(status = Set(ExecutionStatus.Finished))).unsafeRunSync() shouldBe 0
    }

    "should support pagination and sorting" in {
      val clock      = TestClock()
      val templateId = "test"
      val registry   = PostgresWorkflowRegistry(xa = xa, clock = clock).unsafeRunSync()

      List("a", "b", "c").foreach { s =>
        registry.upsertInstance(dummyAW(WorkflowInstanceId(templateId, s)), ExecutionStatus.Running).unsafeRunSync()
        clock.advanceBy(1.second)
      }

      val asc = registry.search(templateId, WorkflowSearch.Query(sort = Some(WorkflowSearch.SortBy.CreatedAsc), limit = Some(2))).unsafeRunSync()
      asc.map(_.id.instanceId) shouldBe List("a", "b")

      val desc = registry.search(templateId, WorkflowSearch.Query(sort = Some(WorkflowSearch.SortBy.CreatedDesc), limit = Some(2))).unsafeRunSync()
      desc.map(_.id.instanceId) shouldBe List("c", "b")

      val withOffset =
        registry
          .search(templateId, WorkflowSearch.Query(sort = Some(WorkflowSearch.SortBy.CreatedAsc), limit = Some(1), offset = Some(1)))
          .unsafeRunSync()
      withOffset.map(_.id.instanceId) shouldBe List("b")
    }
  }

  extension [T](v: fs2.Stream[ConnectionIO, T]) {
    def drainUnsafe: List[T] = v.compile.toList.transact(xa).unsafeRunSync()
  }

  def dummyAW(id: WorkflowInstanceId): ActiveWorkflow[WorkflowContext { type State = Null }] = ActiveWorkflow(id, WIO.End(), null)
}
