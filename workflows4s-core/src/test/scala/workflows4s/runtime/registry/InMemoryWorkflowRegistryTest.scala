package workflows4s.runtime.registry

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.testing.{TestClock, TestUtils}
import workflows4s.wio.{ActiveWorkflow, WIO, WorkflowContext}

import scala.concurrent.duration.DurationInt

class InMemoryWorkflowRegistryTest extends AnyFreeSpec with Matchers {

  "InMemoryWorkflowRegistry" - {
    "should store and retrieve workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry[IO](clock)

      val List(id1, id2, id3) = List.fill(3)(TestUtils.randomWfId())

      (for {
        _         <- registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running)
        _         <- registry.upsertInstance(dummyAW(id2), ExecutionStatus.Awaiting)
        _         <- registry.upsertInstance(dummyAW(id3), ExecutionStatus.Finished)
        workflows <- registry.getWorkflows()
      } yield {
        assert(
          workflows.toSet == Set(
            InMemoryWorkflowRegistry.Data(id1, clock.instant, clock.instant, ExecutionStatus.Running, None, Map()),
            InMemoryWorkflowRegistry.Data(id2, clock.instant, clock.instant, ExecutionStatus.Awaiting, None, Map()),
            InMemoryWorkflowRegistry.Data(id3, clock.instant, clock.instant, ExecutionStatus.Finished, None, Map()),
          ),
        )
      }).unsafeRunSync()
    }

    "should update existing workflow instances" in {
      val clock    = TestClock()
      val registry = InMemoryWorkflowRegistry[IO](clock)

      val List(id1, id2) = List.fill(2)(TestUtils.randomWfId())
      val initialTime    = clock.instant

      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Running).unsafeRunSync()
      registry.upsertInstance(dummyAW(id2), ExecutionStatus.Running).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync().toSet == Set(
          InMemoryWorkflowRegistry.Data(id1, initialTime, initialTime, ExecutionStatus.Running, None, Map()),
          InMemoryWorkflowRegistry.Data(id2, initialTime, initialTime, ExecutionStatus.Running, None, Map()),
        ),
      )

      clock.advanceBy(1.second)
      registry.upsertInstance(dummyAW(id1), ExecutionStatus.Finished).unsafeRunSync()

      assert(
        registry.getWorkflows().unsafeRunSync().toSet == Set(
          InMemoryWorkflowRegistry.Data(id1, initialTime, clock.instant, ExecutionStatus.Finished, None, Map()),
          InMemoryWorkflowRegistry.Data(id2, initialTime, initialTime, ExecutionStatus.Running, None, Map()),
        ),
      )
    }
  }

  object DummyCtx extends WorkflowContext {
    type Effect[T] = scala.util.Try[T]
    type Event     = Nothing
    type State     = Null
  }
  def dummyAW(id: WorkflowInstanceId): ActiveWorkflow[DummyCtx.Ctx] = {
    ActiveWorkflow[DummyCtx.Ctx](id, WIO.End[DummyCtx.Ctx](), null)
  }
}
