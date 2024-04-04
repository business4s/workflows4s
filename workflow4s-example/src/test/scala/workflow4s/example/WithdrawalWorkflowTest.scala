package workflow4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.example.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.checks.{ChecksEngine, ChecksInput, ChecksState, Decision}
import workflow4s.example.testuitls.TestUtils.{SimpleQueryResponseOps, SimpleSignalResponseOps}
import workflow4s.wio.model.{WIOModel, WIOModelInterpreter}
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import io.circe.syntax.*

import java.io.File

//noinspection ForwardReference
class WithdrawalWorkflowTest extends AnyFreeSpec with MockFactory {

  "Withdrawal Example" - {

    "happy path" in new Fixture {
      assert(actor.queryData() == WithdrawalData.Empty(txId))

      withFeeCalculation(fees)
      withMoneyOnHold(success = true)
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

    "reject in validation" in new Fixture {
      actor.init(CreateWithdrawal(-100, recipient))
      assert(actor.queryData() == WithdrawalData.Completed.Failed("Amount must be positive"))

      checkRecovery()
    }

    "reject in funds lock" in new Fixture {
      withFeeCalculation(fees)
      withMoneyOnHold(success = false)

      actor.init(CreateWithdrawal(amount, recipient))
      assert(actor.queryData() == WithdrawalData.Completed.Failed("Not enough funds on the user's account"))

      checkRecovery()
    }

    "reject in execution initiation" in new Fixture {
      withFeeCalculation(fees)
      withMoneyOnHold(success = true)
      withExecutionInitiated(success = false)
      withFundsLockCancelled()

      actor.init(CreateWithdrawal(amount, recipient))
      assert(actor.queryData() == WithdrawalData.Completed.Failed("Rejected by execution engine"))

      checkRecovery()
    }

    "reject in execution confirmation" in new Fixture {
      withFeeCalculation(fees)
      withMoneyOnHold(success = true)
      withExecutionInitiated(success = true)
      withFundsLockCancelled()

      actor.init(CreateWithdrawal(amount, recipient))
      actor.confirmExecution(WithdrawalSignal.ExecutionCompleted.Failed)
      assert(actor.queryData() == WithdrawalData.Completed.Failed("Execution failed"))

      checkRecovery()
    }

    "render model" in new Fixture {
      val model     = getModel(new WithdrawalWorkflow(service, DummyChecksEngine).workflow)
      val modelJson = model.asJson
      print(modelJson.spaces2)
    }

    "render bpmn model" in new Fixture {
      val wf            = new WithdrawalWorkflow(service, DummyChecksEngine)
      val model         = getModel(wf.workflow)
      val bpmnModel     = BPMNConverter.convert(model, "withdrawal-example")
      Bpmn.writeModelToFile(new File("src/test/resources/withdrawal-example-bpmn.bpmn"), bpmnModel)
      val modelDecl     = getModel(wf.workflowDeclarative)
      val bpmnModelDecl = BPMNConverter.convert(modelDecl, "withdrawal-example")
      Bpmn.writeModelToFile(new File("src/test/resources/withdrawal-example-bpmn-declarative.bpmn"), bpmnModelDecl)
    }
  }

  trait Fixture extends StrictLogging {
    val journal    = new InMemoryJournal[WithdrawalEvent]
    lazy val actor = createActor(journal)

    def checkRecovery() = {
      logger.debug("Checking recovery")
      val secondActor = createActor(journal)
      assert(actor.queryData() == secondActor.queryData())
    }

    def createActor(journal: InMemoryJournal[WithdrawalEvent]) = {
      val actor = new WithdrawalActor(journal)
      actor.recover()
      actor
    }

    val txId                                                                                              = "abc"
    val amount                                                                                            = 100
    val recipient                                                                                         = Iban("A")
    val fees                                                                                              = Fee(11)
    val externalId                                                                                        = "external-id-1"
    val service                                                                                           = mock[WithdrawalService]
    val workflow: WithdrawalWorkflow.Context.WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
      new WithdrawalWorkflow(service, DummyChecksEngine).workflowDeclarative

    def withFeeCalculation(fee: Fee)             =
      (service.calculateFees _).expects(*).returning(IO(fee))
    def withMoneyOnHold(success: Boolean)        =
      (service.putMoneyOnHold _).expects(*).returning(IO(Either.cond(success, (), WithdrawalService.NotEnoughFunds())))
    def withExecutionInitiated(success: Boolean) =
      (service.initiateExecution _)
        .expects(*, *)
        .returning(IO(if (success) ExecutionResponse.Accepted(externalId) else ExecutionResponse.Rejected("Rejected by execution engine")))
    def withFundsReleased()                      =
      (service.releaseFunds _)
        .expects(*)
        .returning(IO.unit)
    def withFundsLockCancelled()                 =
      (service.cancelFundsLock _)
        .expects()
        .returning(IO.unit)

    object DummyChecksEngine extends ChecksEngine {
      override def runChecks: ChecksEngine.Context.WIO[ChecksInput, Nothing, ChecksState.Decided] =
        ChecksEngine.Context.WIO.pure(ChecksState.Decided(Map(), Decision.ApprovedBySystem()))
    }

    class WithdrawalActor(journal: InMemoryJournal[WithdrawalEvent]) {
      val delegate                                                         = SimpleActor.create[WithdrawalWorkflow.Context.type, WithdrawalData.Empty](workflow, WithdrawalData.Empty(txId), journal)
      def init(req: CreateWithdrawal): Unit                                = {
        delegate.handleSignal(WithdrawalWorkflow.createWithdrawalSignal)(req).extract
      }
      def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit = {
        delegate.handleSignal(WithdrawalWorkflow.executionCompletedSignal)(req).extract
      }

      def queryData(): WithdrawalData = delegate.handleQuery(WithdrawalWorkflow.dataQuery)(()).extract

      def recover(): Unit = delegate.recover()

    }

    def getModel(wio: WithdrawalWorkflow.Context.WIO[?, ?, ?]): WIOModel = {
      val m = new WIOModelInterpreter(WithdrawalWorkflow.Context)
      m.WIOModelInterpreter.run(wio)
    }

  }

}
