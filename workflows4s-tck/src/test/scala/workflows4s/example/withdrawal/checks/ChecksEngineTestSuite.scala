package workflows4s.example.withdrawal.checks

import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Inside.inside
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.WorkflowTestAdapter

import scala.reflect.Selectable.reflectiveSelectable

trait ChecksEngineTestSuite[F[_]] extends AnyFreeSpecLike {

  given effect: Effect[F]

  val testContext: ChecksEngineTestContext[F] = new ChecksEngineTestContext[F]

  def createTrackingCheck(pendingCount: Int): Check[F, Unit] & { def runNum: Int }

  def checkEngineTests(
      testAdapter: => WorkflowTestAdapter[F, testContext.Context.Ctx],
  ): Unit = {

    "re-run pending checks until complete" in new Fixture(testAdapter) {
      val check = createTrackingCheck(pendingCount = 2)

      val actor = createWorkflow(List(check))
      actor.run()
      assert(check.runNum == 1)

      inside(actor.state) { case x: ChecksState.Pending =>
        assert(x.results == Map(check.key -> CheckResult.Pending()))
      }

      // Advance clock using Scala FiniteDuration
      adapter.clock.advanceBy(ChecksEngine.retryBackoff)
      actor.run()
      assert(check.runNum == 2)
      inside(actor.state) { case x: ChecksState.Pending =>
        assert(x.results == Map(check.key -> CheckResult.Pending()))
      }

      adapter.clock.advanceBy(ChecksEngine.retryBackoff)
      actor.run()
      assert(actor.state == ChecksState.Decided(Map(check.key -> CheckResult.Approved()), Decision.ApprovedBySystem()))

      checkRecovery(actor)
    }

    "timeout checks" in new Fixture(testAdapter) {
      val check = StaticCheck.pending[F]()
      val actor = createWorkflow(List(check))
      actor.run()

      adapter.clock.advanceBy(ChecksEngine.timeoutThreshold)
      adapter.executeDueWakeup(actor.wf)

      assert(actor.state == ChecksState.Executed(Map(check.key -> CheckResult.TimedOut())))

      actor.review(ReviewDecision.Approve)
      assert(
        actor.state == ChecksState.Decided(
          Map(check.key -> CheckResult.TimedOut()),
          Decision.ApprovedByOperator(),
        ),
      )
    }

    class Fixture(val adapter: WorkflowTestAdapter[F, testContext.Context.Ctx]) extends StrictLogging {

      def createWorkflow(checks: List[Check[F, Unit]]): ChecksActor = {
        val checksEngine = testContext.createEngine()
        val wf           = adapter.runWorkflow(
          checksEngine.runChecks.provideInput(ChecksInput((), checks)),
          null: ChecksState,
        )
        new ChecksActor(wf)
      }

      def checkRecovery(firstActor: ChecksActor): Unit = {
        val originalState  = firstActor.state
        val secondActor    = adapter.recover(firstActor.wf)
        val recoveredState = effect.runSyncUnsafe(secondActor.queryState())
        val _              = assert(recoveredState == originalState)
      }

      class ChecksActor(val wf: adapter.Actor) {
        def run(): Unit = effect.runSyncUnsafe(wf.wakeup())

        def state: ChecksState = effect.runSyncUnsafe(wf.queryState())

        def review(decision: ReviewDecision): Unit = {
          val _ = effect.runSyncUnsafe(wf.deliverSignal(ChecksEngine.signals, decision))
        }
      }
    }
  }
}
