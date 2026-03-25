package workflows4s.testing

import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.scalatest.freespec.AnyFreeSpecLike
import sourcecode.Text.generate
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.{SignalDef, StepId, TestCtx2, TestState}

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

      "signal redelivery should return original response without re-running side effects" in new Fixture {
        import TestCtx2.*
        var sideEffectCount = 0

        val signalDef = SignalDef[Int, Int](id = "redelivery-test-signal")
        val wio       = WIO
          .handleSignal(signalDef)
          .using[TestState]
          .withSideEffects { (_, req) =>
            IO({
              sideEffectCount += 1
              new RedeliveryTestEvent(req)
            })
          }
          .handleEvent((st, _) => st.addExecuted(StepId.random("signal")))
          .produceResponse((_, evt: RedeliveryTestEvent, _) => evt.req * 10)
          .done

        val wf = createInstance(wio)

        // First delivery - side effect runs
        val response1 = wf.deliverSignal(signalDef, 42)
        assert(response1 == Right(420))
        assert(sideEffectCount == 1)

        // Redelivery - side effect should NOT run again, response should be same
        val response2 = wf.deliverSignal(signalDef, 999)
        assert(response2 == Right(420))
        assert(sideEffectCount == 1)
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
          val registeredWorkflows = runtime.registry.getWorkflows().unsafeRunSync()
          assert(registeredWorkflows.size == 1)
          assert(registeredWorkflows.head.status == status)
        }
        def createInstance(wio: TestCtx2.WIO[TestState, Nothing, TestState]) = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)
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

  // Must be at top level for Pekko serialization
  class RedeliveryTestEvent(val req: Int) extends TestCtx2.Event with Serializable

}
