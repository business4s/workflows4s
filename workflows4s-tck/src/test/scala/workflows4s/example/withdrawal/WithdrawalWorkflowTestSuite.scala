package workflows4s.example.withdrawal

import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.WorkflowTestAdapter

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.JavaDurationOps

trait WithdrawalWorkflowTestSuite[F[_]] extends AnyFreeSpecLike {

  given effect: Effect[F]

  val testContext: WithdrawalWorkflowTestContext[F] = new WithdrawalWorkflowTestContext[F]

  /** Hook for persisting workflow progress diagrams. Override in subclasses to enable diagram generation. */
  def persistProgress(progress: workflows4s.wio.model.WIOExecutionProgress[?], name: String): Unit = ()

  def withdrawalTests(
      testAdapter: => WorkflowTestAdapter[F, testContext.Context.Ctx],
  ): Unit = {

    "happy path" in new Fixture(testAdapter) {
      assert(actor.queryData() == WithdrawalData.Empty)

      service.withFees(fees).withHoldSuccess().withExecutionAccepted(externalId)

      actor.init(CreateWithdrawal(txId, amount, recipient))
      assert(
        actor.queryData() ==
          WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
      )

      persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "happy-path-1")
      actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Succeeded)
      assert(actor.queryData() == WithdrawalData.Completed.Successfully())
      persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "happy-path-2")

      checkRecovery()
    }

    "reject" - {

      "in validation" in new Fixture(testAdapter) {
        actor.init(CreateWithdrawal(txId, -100, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Amount must be positive"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "failed-validation")
        checkRecovery()
      }

      "in funds lock" in new Fixture(testAdapter) {
        service.withFees(fees).withHoldFailure()

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Not enough funds on the user's account"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "failed-funds-lock")

        checkRecovery()
      }

      "in checks" in new Fixture(testAdapter) {
        val check = StaticCheck.rejected[F]()
        service.withFees(fees).withHoldSuccess().withChecks(List(check))

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Transaction rejected in checks"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "failed-checks")

        checkRecovery()
      }

      "in execution initiation" in new Fixture(testAdapter) {
        service.withFees(fees).withHoldSuccess().withExecutionRejected("Rejected by execution engine")

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Rejected by execution engine"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "failed-execution-initiation")

        checkRecovery()
      }

      "in execution confirmation" in new Fixture(testAdapter) {
        service.withFees(fees).withHoldSuccess().withExecutionAccepted(externalId)

        actor.init(CreateWithdrawal(txId, amount, recipient))
        actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Failed)
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Execution failed"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "failed-execution")

        checkRecovery()
      }
    }

    "cancel" - {

      "when waiting for execution confirmation" in new Fixture(testAdapter) {
        service.withFees(fees).withHoldSuccess().withExecutionAccepted(externalId)

        actor.init(CreateWithdrawal(txId, amount, recipient))
        actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "canceled-waiting-for-execution-confirmation")

        checkRecovery()
      }

      "when running checks" in new Fixture(testAdapter) {
        val check = StaticCheck.pending[F]()
        service.withFees(fees).withHoldSuccess().withChecks(List(check))

        actor.init(CreateWithdrawal(txId, amount, recipient))
        inside(actor.queryData()) { case data: WithdrawalData.Checking =>
          assert(data.checkResults.results == Map(check.key -> CheckResult.Pending()))
        }
        actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))
        persistProgress(effect.runSyncUnsafe(actor.wf.getProgress), "canceled-running-checks")

        checkRecovery()
      }
    }

    "retry execution" in new Fixture(testAdapter) {
      assert(actor.queryData() == WithdrawalData.Empty)

      var retryCount                                 = 0
      val executionBehavior: () => ExecutionResponse = () => {
        if retryCount == 0 then {
          retryCount += 1
          throw new RuntimeException("Failed to initiate execution")
        } else {
          ExecutionResponse.Accepted(externalId)
        }
      }

      service.withFees(fees).withHoldSuccess().withExecutionResponse(executionBehavior)

      actor.init(CreateWithdrawal(txId, amount, recipient))
      assert(
        actor.queryData() ==
          WithdrawalData.Checked(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem())),
      )
      adapter.clock.advanceBy(WithdrawalWorkflow.executionRetryDelay.toScala)
      adapter.clock.advanceBy(1.second)
      adapter.executeDueWakeup(actor.wf)
      assert(
        actor.queryData() ==
          WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
      )

      checkRecovery()
    }

    class Fixture(val adapter: WorkflowTestAdapter[F, testContext.Context.Ctx]) extends StrictLogging {
      val txId  = "abc"
      val actor = createActor()

      def checkRecovery(): Unit = {
        logger.debug("Checking recovery")
        val originalState  = effect.runSyncUnsafe(actor.wf.queryState())
        val secondActor    = adapter.recover(actor.wf)
        val recoveredState = eventually {
          effect.runSyncUnsafe(secondActor.queryState())
        }
        val _              = assert(recoveredState == originalState)
      }

      def createActor(): WithdrawalActor = {
        val wf = adapter.runWorkflow(
          workflow,
          WithdrawalData.Empty,
        )
        new WithdrawalActor(wf)
      }

      lazy val amount: BigDecimal                                           = 100
      lazy val recipient: Iban                                              = Iban("A")
      lazy val fees: Fee                                                    = Fee(11)
      lazy val externalId: String                                           = "external-id-1"
      lazy val service: TestWithdrawalService[F]                            = TestWithdrawalService[F]
      lazy val checksEngine: ChecksEngine[F, testContext.ChecksContext.Ctx] =
        new ChecksEngine[F, testContext.ChecksContext.Ctx](testContext.ChecksContext)

      def workflow: testContext.Context.WIO.Initial =
        testContext.createWorkflow(service, checksEngine).workflowDeclarative

      class WithdrawalActor(val wf: adapter.Actor) {
        def init(req: CreateWithdrawal): Unit = {
          val _ = effect.runSyncUnsafe(wf.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, req))
        }

        def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit = {
          val _ = effect.runSyncUnsafe(wf.deliverSignal(WithdrawalWorkflow.Signals.executionCompleted, req))
        }

        def cancel(req: WithdrawalSignal.CancelWithdrawal): Unit = {
          val _ = effect.runSyncUnsafe(wf.deliverSignal(WithdrawalWorkflow.Signals.cancel, req))
        }

        def queryData(): WithdrawalData = effect.runSyncUnsafe(wf.queryState())
      }
    }
  }
}
