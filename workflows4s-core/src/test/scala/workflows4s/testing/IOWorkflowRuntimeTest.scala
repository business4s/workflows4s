package workflows4s.testing

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.effect.Ref
import cats.syntax.all.*
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.wio.{IOTestCtx2, TestState}

/** IO-based workflow runtime test suite for runtimes that use IO effect type (Pekko, Doobie).
  */
object IOWorkflowRuntimeTest {
  trait Suite extends AnyFreeSpecLike {

    def ioWorkflowTests(getRuntime: => IOTestRuntimeAdapter[IOTestCtx2.Ctx]): Unit = {

      "runtime should not allow interrupting a process while another step is running" in new IOFixture {
        def singleRun(i: Int): IO[Unit] = {
          IO(println(s"Running $i iteration")) *> {
            import IOTestCtx2.*

            for {
              longRunningStartedSem  <- Semaphore[IO](0)
              longrunningFinishedSem <- Semaphore[IO](0)
              signalStartedRef       <- Ref[IO].of(false)

              (longRunningStepId, longRunningStep) = IOTestUtils.runIOCustom(longRunningStartedSem.release *> longrunningFinishedSem.acquire)

              (signal, _, signalStep) = IOTestUtils.signalCustom(signalStartedRef.set(true))

              wio = longRunningStep.interruptWith(signalStep.toInterruption)
              wf  = createInstance(wio)

              wakeupFiber <- wf.wakeup().start
              signalFiber <- (longRunningStartedSem.acquire *> wf.deliverSignal(signal, 1)).start

              initialState <- wf.queryState()
              _             = assert(initialState == TestState.empty)

              _ <- longrunningFinishedSem.release
              _ <- wakeupFiber.joinWith(IO(fail("wakeup was cancelled")))

              stateAfterWakeup <- wf.queryState()
              _                 = assert(stateAfterWakeup == TestState(List(longRunningStepId)))

              signalResult  <- signalFiber.joinWith(IO(fail("signal was cancelled"))).attempt
              signalStarted <- signalStartedRef.get

              _ <- IO(assert(!signalStarted))
              _ <- IO(assert(signalResult.isLeft || signalResult.exists(_.isLeft)))

              finalState <- wf.queryState()
              _           = assert(finalState == TestState(List(longRunningStepId)))
            } yield ()
          }
        }

        (1 to 50).toList.traverse_(singleRun).timeout(runtime.testTimeout).unsafeRunSync()
      }

      trait IOFixture {
        val runtime = getRuntime

        def createInstance(wio: IOTestCtx2.WIO[TestState, Nothing, TestState]): runtime.Actor = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)
        }
      }
    }
  }

}
