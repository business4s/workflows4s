package workflow4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside.inside
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.checks.StaticCheck
import workflow4s.example.testuitls.TestUtils.SimpleSignalResponseOps
import workflow4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.withdrawal.checks.*
import workflow4s.example.withdrawal.*
import workflow4s.wio.KnockerUpper
import workflow4s.wio.model.{WIOModel, WIOModelInterpreter}
import workflow4s.wio.simple.SimpleActor

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

//noinspection ForwardReference
class WithdrawalWorkflowTest extends AnyFreeSpec with MockFactory {

  "Withdrawal Example" - {

    "happy path" in new Fixture {
      assert(actor.queryData() == WithdrawalData.Empty(txId))

      withFeeCalculation(fees)
      withMoneyOnHold(success = true)
      withNoChecks()
      withExecutionInitiated(success = true)
      withFundsReleased()

      actor.init(CreateWithdrawal(amount, recipient))
      assert(
        actor.queryData() ==
          WithdrawalData.Executed(txId, amount, recipient, fees, ChecksState.Decided(Map(), Decision.ApprovedBySystem()), externalId),
      )

      actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Succeeded)
      assert(actor.queryData() == WithdrawalData.Completed.Succesfully())

      checkRecovery()
    }
    "reject" - {

      "in validation" in new Fixture {
        actor.init(CreateWithdrawal(-100, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Amount must be positive"))

        checkRecovery()
      }

      "in funds lock" in new Fixture {
        withFeeCalculation(fees)
        withMoneyOnHold(success = false)

        actor.init(CreateWithdrawal(amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Not enough funds on the user's account"))

        checkRecovery()
      }

      "in checks" in new Fixture {
        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withChecks(List(StaticCheck(CheckResult.Rejected())))
        withFundsLockCancelled()

        actor.init(CreateWithdrawal(amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Transaction rejected in checks"))

        checkRecovery()
      }

      "in execution initiation" in new Fixture {
        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(success = false)
        withFundsLockCancelled()

        actor.init(CreateWithdrawal(amount, recipient))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Rejected by execution engine"))

        checkRecovery()
      }

      "in execution confirmation" in new Fixture {
        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(success = true)
        withFundsLockCancelled()

        actor.init(CreateWithdrawal(amount, recipient))
        actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Failed)
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Execution failed"))

        checkRecovery()
      }
    }

    "render model" in new Fixture {
      val wf = new WithdrawalWorkflow(service, DummyChecksEngine)
      TestUtils.renderModelToFile(wf.workflowDeclarative, "withdrawal-example-declarative-model.json")
    }

    "render bpmn model" in new Fixture {
      val wf = new WithdrawalWorkflow(service, DummyChecksEngine)
      TestUtils.renderBpmnToFile(wf.workflow, "withdrawal-example-bpmn.bpmn")
      TestUtils.renderBpmnToFile(wf.workflowDeclarative, "withdrawal-example-bpmn-declarative.bpmn")
    }

    "cancel" - {

      // other tests require concurrent testing
      "when waiting for execution confirmation" in new Fixture {
        withFeeCalculation(fees)
        withMoneyOnHold(success = true)
        withNoChecks()
        withExecutionInitiated(success = true)
        withFundsLockCancelled()

        actor.init(CreateWithdrawal(amount, recipient))
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

        actor.init(CreateWithdrawal(amount, recipient))
        inside(actor.queryData()) { case data: WithdrawalData.Checking =>
          assert(data.checkResults.results == Map(check.key -> CheckResult.Pending()))
        }
        actor.cancel(WithdrawalSignal.CancelWithdrawal("operator-1", "cancelled", acceptStartedExecution = true))
        assert(actor.queryData() == WithdrawalData.Completed.Failed("Cancelled by operator-1. Comment: cancelled"))

        checkRecovery()
      }

    }
  }

  trait Fixture extends StrictLogging {
    lazy val actor   = createActor(List())
    val clock        = new TestClock
    val knockerUpper = KnockerUpper.noop

    def checkRecovery() = {
      logger.debug("Checking recovery")
      val secondActor = createActor(actor.events)
      assert(actor.queryData() == secondActor.queryData())
    }

    def createActor(events: List[WithdrawalEvent]) = {
      val actor = new WithdrawalActor(clock, knockerUpper)
      actor.recover(events)
      actor
    }

    val txId                                                                                              = "abc"
    val amount                                                                                            = 100
    val recipient                                                                                         = Iban("A")
    val fees                                                                                              = Fee(11)
    val externalId                                                                                        = "external-id-1"
    val service: WithdrawalService                                                                        = mock[WithdrawalService]
    def checksEngine: ChecksEngine                                                                        = ChecksEngine
    def workflow: WithdrawalWorkflow.Context.WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
      new WithdrawalWorkflow(service, checksEngine).workflowDeclarative

    def withFeeCalculation(fee: Fee)                            =
      (service.calculateFees _).expects(*).returning(IO(fee))
    def withMoneyOnHold(success: Boolean)                       =
      (service.putMoneyOnHold _).expects(*).returning(IO(Either.cond(success, (), WithdrawalService.NotEnoughFunds())))
    def withExecutionInitiated(success: Boolean)                =
      (service.initiateExecution _)
        .expects(*, *)
        .returning(IO(if (success) ExecutionResponse.Accepted(externalId) else ExecutionResponse.Rejected("Rejected by execution engine")))
    def withFundsReleased()                                     =
      (service.releaseFunds _)
        .expects(*)
        .returning(IO.unit)
    def withFundsLockCancelled()                                =
      (service.cancelFundsLock _)
        .expects()
        .returning(IO.unit)
    def withChecks(list: List[Check[WithdrawalData.Validated]]) =
      (service.getChecks _)
        .expects()
        .returning(list)
        .anyNumberOfTimes()
    def withNoChecks()                                          = withChecks(List())

    object DummyChecksEngine extends ChecksEngine {
      override def runChecks: ChecksEngine.Context.WIO[ChecksInput, Nothing, ChecksState.Decided] =
        ChecksEngine.Context.WIO.pure(ChecksState.Decided(Map(), Decision.ApprovedBySystem()))
    }

    class WithdrawalActor(clock: Clock, knockerUpper: KnockerUpper) {
      val delegate: SimpleActor[WithdrawalData] { type Ctx = WithdrawalWorkflow.Context.type } =
        SimpleActor.create[WithdrawalWorkflow.Context.type, WithdrawalData.Empty](workflow, WithdrawalData.Empty(txId), clock, knockerUpper)
      def init(req: CreateWithdrawal): Unit                                                    = {
        delegate.handleSignal(WithdrawalWorkflow.Signals.createWithdrawal)(req).extract
      }
      def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit                     = {
        delegate.handleSignal(WithdrawalWorkflow.Signals.executionCompleted)(req).extract
      }
      def cancel(req: WithdrawalSignal.CancelWithdrawal): Unit                                 = {
        delegate.handleSignal(WithdrawalWorkflow.Signals.cancel)(req).extract
      }

      def queryData(): WithdrawalData = delegate.state

      def events: List[WithdrawalEvent]                = delegate.events.toList
      def recover(events: List[WithdrawalEvent]): Unit = delegate.recover(events)

    }

    def getModel(wio: WithdrawalWorkflow.Context.WIO[?, ?, ?]): WIOModel = {
      WIOModelInterpreter.run(wio)
    }

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
