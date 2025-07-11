package workflows4s.testing

import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits.toFoldableOps
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

    def workflowTests(getRuntime: => TestRuntimeAdapter[TestCtx2.Ctx]) = {

      "runtime should not allow interrupting a process while another step is running" in new Fixture {
        def singleRun(i: Int): IO[Unit] = {
          IO(println(s"Running $i iteration")) *> {
            import TestCtx2.*

            for {
              longRunningStartedSem  <- Semaphore[IO](0)
              longrunningFinishedSem <- Semaphore[IO](0)
              signalStartedRef       <- Ref[IO].of(false)

              (longRunningStepId, longRunningStep) = TestUtils.runIOCustom(longRunningStartedSem.release *> longrunningFinishedSem.acquire)

              (signal, _, signalStep) = TestUtils.signalCustom(signalStartedRef.set(true))

              wio = longRunningStep.interruptWith(signalStep.toInterruption)
              wf  = createInstance(wio)

              wakeupFiber <- IO(wf.wakeup()).start
              signalFiber <- (longRunningStartedSem.acquire *> IO(wf.deliverSignal(signal, 1))).start

              _ = assert(wf.queryState() == TestState.empty)

              _ <- longrunningFinishedSem.release
              _ <- wakeupFiber.joinWith(IO(fail("wakeup was cancelled")))
              _  = assert(wf.queryState() == TestState(List(longRunningStepId)))

              signalResult  <- signalFiber.joinWith(IO(fail("signal was cancelled"))).attempt
              signalStarted <- signalStartedRef.get

              _ <- IO(assert(!signalStarted))
              _ <- IO(assert(signalResult.isLeft || signalResult.exists(_.isLeft)))
              _ <- IO(assert(wf.queryState() == TestState(List(longRunningStepId))))
            } yield ()
          }
        }

        (1 to 50).toList.traverse_(singleRun).unsafeRunSync()
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
        val registry = InMemoryWorkflowRegistry(runtime.clock).unsafeRunSync()

        def expectRegistryEntry(status: ExecutionStatus)                     = {
          val registeredWorkflows = registry.getWorkflows().unsafeRunSync()
          assert(registeredWorkflows.size == 1)
          assert(registeredWorkflows.head.status == status)
        }
        def createInstance(wio: TestCtx2.WIO[TestState, Nothing, TestState]) = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty, registry.agent)
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

}
