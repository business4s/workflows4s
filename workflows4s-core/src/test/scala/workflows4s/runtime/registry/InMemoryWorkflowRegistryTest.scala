package workflows4s.runtime.registry

import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.{TestClock, TestUtils}

import scala.concurrent.duration.DurationInt

class InMemoryWorkflowRegistryTest extends AnyFreeSpec with Matchers {

  "InMemoryWorkflowRegistry" - {
    "should store and retrieve workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry(clock).unsafeRunSync()
      val agent    = registry.agent

      val List(id1, id2, id3) = List.fill(3)(TestUtils.randomWfId())

      (for {
        _         <- agent.upsertInstance(id1, ExecutionStatus.Running)
        _         <- agent.upsertInstance(id2, ExecutionStatus.Awaiting)
        _         <- agent.upsertInstance(id3, ExecutionStatus.Finished)
        workflows <- registry.getWorkflows()
      } yield {
        assert(
          workflows == List(
            InMemoryWorkflowRegistry.Data(id1, clock.instant, clock.instant, ExecutionStatus.Running),
            InMemoryWorkflowRegistry.Data(id2, clock.instant, clock.instant, ExecutionStatus.Awaiting),
            InMemoryWorkflowRegistry.Data(id3, clock.instant, clock.instant, ExecutionStatus.Finished),
          ),
        )
      }).unsafeRunSync()
    }

    "should update existing workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry(clock).unsafeRunSync()

      val List(id1, id2) = List.fill(2)(TestUtils.randomWfId())
      val initialTime    = clock.instant

      registry.agent.upsertInstance(id1, ExecutionStatus.Running).unsafeRunSync()
      registry.agent.upsertInstance(id2, ExecutionStatus.Running).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync() == List(
          InMemoryWorkflowRegistry.Data(id1, initialTime, initialTime, ExecutionStatus.Running),
          InMemoryWorkflowRegistry.Data(id2, initialTime, initialTime, ExecutionStatus.Running),
        ),
      )

      clock.advanceBy(1.second)
      registry.agent.upsertInstance(id1, ExecutionStatus.Finished).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync() == List(
          InMemoryWorkflowRegistry.Data(id1, initialTime, clock.instant, ExecutionStatus.Finished),
          InMemoryWorkflowRegistry.Data(id2, initialTime, initialTime, ExecutionStatus.Running),
        ),
      )
    }
  }
}
