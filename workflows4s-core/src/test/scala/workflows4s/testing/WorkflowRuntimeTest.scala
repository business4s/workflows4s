package workflows4s.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpecLike
import sourcecode.Text.generate
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.{TestCtx2, TestState}

import java.util.concurrent.Semaphore
import scala.annotation.{nowarn, unused}

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

      s"runtime should not allow interrupting a process while another step is running" in new Fixture {
        for { i <- 1.to(100) } {
          println(s"Running $i iteration")
          import TestCtx2.*
          val longRunningStartedSem                         = new Semaphore(0)
          val (longRunningStepId, longRunningStep, unblock) = {
            val semaphore = new Semaphore(0)
            TestUtils.runIOCustom(IO({
              longRunningStartedSem.release()
              semaphore.acquire()
            })) :* (() => semaphore.release())
          }
          @unused
          var signalStarted                                 = false
          val (signal, _, signalStep)                       = TestUtils.signalCustom(IO({
            signalStarted = true
          }))

          val wio = longRunningStep.interruptWith(signalStep.toInterruption)
          val wf  = createInstance(wio)

          val wakupFiber = IO(wf.wakeup()).start.unsafeRunSync()

          val signalFiber = IO({
            longRunningStartedSem.acquire()
            wf.deliverSignal(signal, 1)
          }).start.unsafeRunSync()

          // No state change is expected while the first step is running
          assert(wf.queryState() == TestState.empty)

          unblock()
          wakupFiber.joinWith(failOnCancel).unsafeRunSync()
          assert(wf.queryState() == TestState(List(longRunningStepId)))

          val signalResult = signalFiber.joinWith(failOnCancel).attempt.unsafeRunSync()
          // this is the most important part of the test.
          // We cannot allow for sideeffect to even start running, if workflow is already locked
          assert(!signalStarted)
          assert(signalResult.isLeft || signalResult.exists(_.isLeft))
          assert(wf.queryState() == TestState(List(longRunningStepId)))
        }
      }

      "workflow registry interaction" - {
        "register execution status for io" in new Fixture {

          val exception = new Exception("IO failed")
          @nowarn("msg=unused private member")
          var failing   = true
          val ioLogic   = IO(if failing then throw exception else ())

          val wf = createInstance(runSpecificIO(ioLogic))

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

          runtime.clock.advanceBy(duration)
          wf.wakeup()

          expectRegistryEntry(ExecutionStatus.Finished)
        }

      }

      trait Fixture {
        val runtime  = getRuntime
        val registry = InMemoryWorkflowRegistry[WfId](runtime.clock).unsafeRunSync()
        val wfType   = "wf1"

        def expectRegistryEntry(status: ExecutionStatus)                     = {
          val registeredWorkflows = registry.getWorkflows().unsafeRunSync()
          assert(registeredWorkflows.size == 1)
          assert(registeredWorkflows.head.status == status)
        }
        def createInstance(wio: TestCtx2.WIO[TestState, Nothing, TestState]) = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty, registry.getAgent(wfType))
        }
      }

    }
  }

  private def runSpecificIO(effect: IO[Any]) = {
    import TestCtx2.*
    WIO
      .runIO[TestState](_ => effect.as(TestCtx2.SimpleEvent("")))
      .handleEvent((st, _) => st)
      .done
  }
  
  private def failOnCancel = IO.raiseError(new Exception("Fiber cancelled"))

}
