package workflows4s.example

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues.*
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.{AnyFreeSpec, AnyFreeSpecLike}
import workflows4s.example.WithdrawalWorkflowTest.DummyChecksEngine
import workflows4s.example.checks.StaticCheck
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflows4s.example.withdrawal.checks.*

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

//noinspection ForwardReference
class WithdrawalWorkflowTest extends AnyFreeSpec with MockFactory with WithdrawalWorkflowTest.Suite {

  "in-memory-sync" - {
    withdrawalTests(TestRuntimeAdapter.InMemorySync())
  }
  "in-memory" - {
    withdrawalTests(TestRuntimeAdapter.InMemory())
  }

  "render model" in {
    val wf = new WithdrawalWorkflow(null, DummyChecksEngine)
    TestUtils.renderModelToFile(wf.workflowDeclarative, "withdrawal-example-declarative-model.json")
  }

  "render bpmn model" in {
    val wf = new WithdrawalWorkflow(null, DummyChecksEngine)
    TestUtils.renderBpmnToFile(wf.workflow, "withdrawal-example-bpmn.bpmn")
    TestUtils.renderBpmnToFile(wf.workflowDeclarative, "withdrawal-example-bpmn-declarative.bpmn")
  }
  "render mermaid model" in {
    val wf = new WithdrawalWorkflow(null, DummyChecksEngine)
    TestUtils.renderMermaidToFile(wf.workflow, "withdrawal-example.mermaid")
    TestUtils.renderMermaidToFile(wf.workflowDeclarative, "withdrawal-example-declarative.mermaid")
  }
}
object WithdrawalWorkflowTest {

  trait Suite extends AnyFreeSpecLike with MockFactory {

    def withdrawalTests(getRuntime: => TestRuntimeAdapter[WithdrawalWorkflow.Context.Ctx]) = {

      "happy path" in new Fixture {
        assert(actor.queryData() == WithdrawalData.Empty)

        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(success = true)
        withFundsReleased()

        actor.init(CreateWithdrawal(txId, amount, recipient))
        assert(
          actor.queryData() ==
            WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
        )

        actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Succeeded)
        assert(actor.queryData() == WithdrawalData.Completed.Successfully())

        checkRecovery()
      }
      "reject" - {

        "in validation" in new Fixture {
          actor.init(CreateWithdrawal(txId, -100, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Amount must be positive"))

          checkRecovery()
        }

        "in funds lock" in new Fixture {
          withFeeCalculation(fees)
          withMoneyOnHold(success = false)

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Not enough funds on the user's account"))

          checkRecovery()
        }

        "in checks" in new Fixture {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withChecks(List(StaticCheck(CheckResult.Rejected())))
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Transaction rejected in checks"))

          checkRecovery()
        }

        "in execution initiation" in new Fixture {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = false)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Rejected by execution engine"))

          checkRecovery()
        }

        "in execution confirmation" in new Fixture {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = true)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Failed)
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Execution failed"))

          checkRecovery()
        }
      }

      "cancel" - {

        // other tests require concurrent testing
        "when waiting for execution confirmation" in new Fixture {
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withNoChecks()
          withExecutionInitiated(success = true)
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))

          checkRecovery()
        }

        "when running checks" in new Fixture {
          val check = StaticCheck(CheckResult.Pending())
          withFeeCalculation(fees)
          withMoneyOnHold(success = true)
          withChecks(List(check))
          withFundsLockCancelled()

          actor.init(CreateWithdrawal(txId, amount, recipient))
          inside(actor.queryData()) { case data: WithdrawalData.Checking =>
            assert(data.checkResults.results == Map(check.key -> CheckResult.Pending()))
          }
          actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
          assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))

          checkRecovery()
        }

      }

      trait Fixture extends StrictLogging {
        val runtime = getRuntime
        val txId    = "abc"
        val clock   = new TestClock
        val actor   = createActor()

        def checkRecovery() = {
          logger.debug("Checking recovery")
          val originalState  = actor.wf.queryState()
          val secondActor    = runtime.recover(actor.wf)
          // seems sometimes querying state from fresh actor gets flaky
          val recoveredState = eventually {
            secondActor.queryState()
          }
          assert(recoveredState == originalState)
        }

        def createActor() = {
          val wf    = runtime
            .runWorkflow(
              workflow,
              WithdrawalData.Empty,
              clock,
            )
          val actor = new WithdrawalActor(wf)
          actor
        }

        lazy val amount                     = 100
        lazy val recipient                  = Iban("A")
        lazy val fees                       = Fee(11)
        lazy val externalId                 = "external-id-1"
        lazy val service: WithdrawalService = mock[WithdrawalService]

        def checksEngine: ChecksEngine = ChecksEngine

        def workflow: WithdrawalWorkflow.Context.WIO.Initial =
          new WithdrawalWorkflow(service, checksEngine).workflowDeclarative

        def withFeeCalculation(fee: Fee) =
          (service.calculateFees).expects(*).returning(IO(fee))

        def withMoneyOnHold(success: Boolean) =
          (service.putMoneyOnHold).expects(*).returning(IO(Either.cond(success, (), WithdrawalService.NotEnoughFunds())))

        def withExecutionInitiated(success: Boolean) =
          (service.initiateExecution)
            .expects(*, *)
            .returning(IO(if (success) ExecutionResponse.Accepted(externalId) else ExecutionResponse.Rejected("Rejected by execution engine")))

        def withFundsReleased() =
          (service.releaseFunds)
            .expects(*)
            .returning(IO.unit)

        def withFundsLockCancelled() =
          ((() => service.cancelFundsLock()))
            .expects()
            .returning(IO.unit)

        def withChecks(list: List[Check[WithdrawalData.Validated]]) =
          ((() => service.getChecks()))
            .expects()
            .returning(list)
            .anyNumberOfTimes()

        def withNoChecks() = withChecks(List())

        class WithdrawalActor(val wf: runtime.Actor) {
          def init(req: CreateWithdrawal): Unit = {
            wf.deliverSignal(WithdrawalWorkflow.Signals.createWithdrawal, req).value
            wf.wakeup()
          }

          def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit = {
            wf.deliverSignal(WithdrawalWorkflow.Signals.executionCompleted, req).value
          }

          def cancel(req: WithdrawalSignal.CancelWithdrawal): Unit = {
            wf.deliverSignal(WithdrawalWorkflow.Signals.cancel, req).value
          }

          def queryData(): WithdrawalData = wf.queryState()
        }

      }

    }
  }

  object DummyChecksEngine extends ChecksEngine {
    override def runChecks: ChecksEngine.Context.WIO[ChecksInput, Nothing, ChecksState.Decided] =
      ChecksEngine.Context.WIO.pure(ChecksState.Decided(Map(), Decision.ApprovedBySystem())).autoNamed
  }

}

class TestClock extends Clock with StrictLogging {
  var instant_ : Instant                       = Instant.now
  def setInstant(instant: Instant): Unit       = this.instant_ = instant
  def instant: Instant                         = instant_
  def getZone: ZoneId                          = ZoneOffset.UTC
  override def withZone(zoneId: ZoneId): Clock = ???

  def advanceBy(duration: FiniteDuration) = {
    instant_ = this.instant_.plus(duration.toJava)
    logger.debug(s"Advancing time by ${duration} to ${instant_}")
  }
}
