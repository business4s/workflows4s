package workflows4s.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpecLike
import sourcecode.Text.generate
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.{TestCtx2, TestState}

import scala.annotation.nowarn

class WorkflowRuntimeTest extends WorkflowRuntimeTest.Suite {

  "in-memory" - {
    workflowTests(TestRuntimeAdapter.InMemory[TestCtx2.Ctx]())
  }
  "in-memory-sync" - {
    workflowTests(TestRuntimeAdapter.InMemorySync[TestCtx2.Ctx]())
  }

}

object WorkflowRuntimeTest {
  trait Suite extends AnyFreeSpecLike {

    def workflowTests[WfId](getRuntime: => TestRuntimeAdapter[TestCtx2.Ctx, WfId]) = {

      "workflow registry interaction" - {
        "register execution status for io" in new Fixture {

          val exception = new Exception("IO failed")
          @nowarn("msg=unused private member")
          var failing   = true
          val ioLogic   = IO(if failing then throw exception else ())

          val wf = createInstance(failingRunIO(ioLogic))

          val thrown = intercept[Exception](wf.wakeup())
          assert(thrown == exception)
          expectRegistryEntry(ExecutionStatus.Running)

          failing = false
          wf.wakeup()
          expectRegistryEntry(ExecutionStatus.Finished)

        }
        "register execution status for signal" in new Fixture {
          val (signal, _, signalStep) = TestUtils.signal

          val wf = createInstance(signalStep)
          wf.wakeup()
          expectRegistryEntry(ExecutionStatus.Awaiting)

          wf.deliverSignal(signal, 1).value
          expectRegistryEntry(ExecutionStatus.Finished)
        }
        "register execution status for timer" in new Fixture {
          val (duration, timerStep) = TestUtils.timer()

          val wf = createInstance(timerStep)
          wf.wakeup()

          expectRegistryEntry(ExecutionStatus.Awaiting)

          clock.advanceBy(duration)
          wf.wakeup()

          expectRegistryEntry(ExecutionStatus.Finished)
        }

      }

      trait Fixture {
        val runtime  = getRuntime
        val clock    = new TestClock
        val registry = InMemoryWorkflowRegistry[WfId](clock).unsafeRunSync()
        val wfType   = "wf1"

        def expectRegistryEntry(status: ExecutionStatus)                     = {
          val registeredWorkflows = registry.getWorkflows().unsafeRunSync()
          assert(registeredWorkflows.size == 1)
          assert(registeredWorkflows.head.status == status)
        }
        def createInstance(wio: TestCtx2.WIO[TestState, Nothing, TestState]) = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty, clock, registry.getAgent(wfType))
        }
      }

    }
  }

  private def failingRunIO(effect: IO[Any]) = {
    import TestCtx2.*
    WIO
      .runIO[TestState](_ => effect.as(TestCtx2.SimpleEvent("")))
      .handleEvent((st, _) => st)
      .done
  }

}
