package workflows4s.doobie.testing

import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.wio.TestState

/** Result-based workflow runtime test suite for doobie runtimes.
  */
object ResultWorkflowRuntimeTest {
  trait Suite extends AnyFreeSpecLike {

    def resultWorkflowTests(getRuntime: => ResultTestRuntimeAdapter[ResultTestCtx2.Ctx]): Unit = {

      "runtime should not allow interrupting a process while another step is running" in new ResultFixture {
        def singleRun(i: Int): IO[Unit] = {
          IO(println(s"Running $i iteration")) *> {
            import ResultTestCtx2.*

            for {
              longRunningStartedSem  <- Semaphore[IO](0)
              longrunningFinishedSem <- Semaphore[IO](0)
              signalStartedRef       <- Ref[IO].of(false)

              // Create Result-based workflow operations using IO semaphores via liftIO
              (longRunningStepId, longRunningStep) =
                ResultTestUtils.runIOCustom(
                  cats.data.Kleisli(liftIO => liftIO.liftIO(longRunningStartedSem.release *> longrunningFinishedSem.acquire)),
                )

              (signal, _, signalStep) = ResultTestUtils.signalCustom(
                                          cats.data.Kleisli(liftIO => liftIO.liftIO(signalStartedRef.set(true))),
                                        )

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

        (1 to 50).toList.traverse_(singleRun).unsafeRunSync()
      }

      trait ResultFixture {
        val runtime = getRuntime

        def createInstance(wio: ResultTestCtx2.WIO[TestState, Nothing, TestState]): runtime.Actor = {
          runtime.runWorkflow(wio.provideInput(TestState.empty), TestState.empty)
        }
      }
    }
  }

}
