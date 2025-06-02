package workflows4s.runtime.registry

import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.TestClock

import java.util.UUID
import scala.concurrent.duration.DurationInt

class InMemoryWorkflowRegistryTest extends AnyFreeSpec with Matchers {

  "InMemoryWorkflowRegistry" - {
    "should store and retrieve workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry[UUID](clock).unsafeRunSync()
      val wfType1  = "test-workflow-1"
      val wfType2  = "test-workflow-2"

      val agent  = registry.getAgent(wfType1)
      val agent2 = registry.getAgent(wfType2)

      val List(id1, id2, id3) = List.fill(3)(UUID.randomUUID())

      agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      agent.upsertInstance(id2, ExecutionStatus.Awaiting).unsafeRunSync()
      agent2.upsertInstance(id3, ExecutionStatus.Finished).unsafeRunSync()

      val workflows = registry.getWorkflows().unsafeRunSync()

      assert(
        workflows == List(
          InMemoryWorkflowRegistry.Data(id1, wfType1, clock.instant, clock.instant, ExecutionStatus.Running),
          InMemoryWorkflowRegistry.Data(id2, wfType1, clock.instant, clock.instant, ExecutionStatus.Awaiting),
          InMemoryWorkflowRegistry.Data(id3, wfType2, clock.instant, clock.instant, ExecutionStatus.Finished),
        ),
      )
    }

    "should update existing workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry[UUID](clock).unsafeRunSync()
      val wfType   = "test-workflow-1"
      val agent    = registry.getAgent(wfType)

      val List(id1, id2) = List.fill(2)(UUID.randomUUID())
      val initialTime    = clock.instant

      agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      agent.upsertInstance(id2, ExecutionStatus.Running).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync() == List(
          InMemoryWorkflowRegistry.Data(id1, wfType, initialTime, initialTime, ExecutionStatus.Running),
          InMemoryWorkflowRegistry.Data(id2, wfType, initialTime, initialTime, ExecutionStatus.Running),
        ),
      )

      clock.advanceBy(1.second)
      agent.upsertInstance(id1, ExecutionStatus.Finished).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync() == List(
          InMemoryWorkflowRegistry.Data(id1, wfType, initialTime, clock.instant, ExecutionStatus.Finished),
          InMemoryWorkflowRegistry.Data(id2, wfType, initialTime, initialTime, ExecutionStatus.Running),
        ),
      )
    }
    "should separate ids by type" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry[UUID](clock).unsafeRunSync()
      val wfType1  = "test-workflow-1"
      val wfType2  = "test-workflow-2"
      val agent1   = registry.getAgent(wfType1)
      val agent2   = registry.getAgent(wfType2)

      val id = UUID.randomUUID()

      agent1.upsertInstance(id, ExecutionStatus.Awaiting).unsafeRunSync()
      agent2.upsertInstance(id, ExecutionStatus.Finished).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync() == List(
          InMemoryWorkflowRegistry.Data(id, wfType1, clock.instant, clock.instant, ExecutionStatus.Awaiting),
          InMemoryWorkflowRegistry.Data(id, wfType2, clock.instant, clock.instant, ExecutionStatus.Finished),
        ),
      )

    }
  }
}
