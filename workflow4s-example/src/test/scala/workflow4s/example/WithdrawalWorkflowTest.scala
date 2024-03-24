package workflow4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.example.WithdrawalEvent.ExecutionCompleted
import workflow4s.example.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.checks.{ChecksEngine, ChecksInput, ChecksState, Decision}
import workflow4s.wio.model.WIOModelInterpreter
import workflow4s.wio.simple.SimpleActor.EventResponse
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import workflow4s.wio.{ActiveWorkflow, Interpreter, WIO}

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
      val model     = WIOModelInterpreter.run(new WithdrawalWorkflow(service, DummyChecksEngine).workflow)
      import io.circe.syntax._
      val modelJson = model.asJson
      print(modelJson.spaces2)
    }

    "render bpmn model" in new Fixture {
      val wf            = new WithdrawalWorkflow(service, DummyChecksEngine)
      val model         = WIOModelInterpreter.run(wf.workflow)
      val bpmnModel     = BPMNConverter.convert(model, "withdrawal-example")
      Bpmn.writeModelToFile(new File("src/test/resources/withdrawal-example-bpmn.bpmn"), bpmnModel)
      val modelDecl     = WIOModelInterpreter.run(wf.workflowDeclarative)
      val bpmnModelDecl = BPMNConverter.convert(modelDecl, "withdrawal-example")
      Bpmn.writeModelToFile(new File("src/test/resources/withdrawal-example-bpmn-declarative.bpmn"), bpmnModelDecl)
    }
  }

  trait Fixture extends StrictLogging {
    val journal    = new InMemoryJournal
    lazy val actor = createActor(journal)

    def checkRecovery() = {
      logger.debug("Checking recovery")
      val secondActor = createActor(journal)
      assert(actor.queryData() == secondActor.queryData())
    }

    def createActor(journal: InMemoryJournal) = {
      val actor = new WithdrawalActor(journal)
      actor.recover()
      actor
    }

    val txId       = "abc"
    val amount     = 100
    val recipient  = Iban("A")
    val fees       = Fee(11)
    val externalId = "external-id-1"
    val service    = mock[WithdrawalService]
    val workflow   = new WithdrawalWorkflow(service, DummyChecksEngine).workflowDeclarative

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
      override def runChecks: WIO[Nothing, Unit, ChecksInput, ChecksState.Decided] =
        WIO.pure.state(ChecksState.Decided(Map(), Decision.ApprovedBySystem()))
    }

    class WithdrawalActor(journal: InMemoryJournal) {
      val delegate = SimpleActor.create(workflow, WithdrawalData.Empty(txId), journal)
      def init(req: CreateWithdrawal): Unit = {
        delegate.handleSignal(WithdrawalWorkflow.createWithdrawalSignal)(req).extract
      }
      def confirmExecution(req: WithdrawalSignal.ExecutionCompleted): Unit = {
        delegate.handleSignal(WithdrawalWorkflow.executionCompletedSignal)(req).extract
      }

      def queryData(): WithdrawalData = delegate.handleQuery(WithdrawalWorkflow.dataQuery)(()).extract

      def recover(): Unit = delegate.recover()

    }

  }

  implicit class SimpleSignalResponseOps[Resp](value: SimpleActor.SignalResponse[Resp]) {
    def extract: Resp = value match {
      case SimpleActor.SignalResponse.Ok(result)             => result
      case SimpleActor.SignalResponse.UnexpectedSignal(desc) => throw new IllegalArgumentException(s"Unexpected signal: $desc")
    }
  }
  implicit class SimpleQueryResponseOps[Resp](value: SimpleActor.QueryResponse[Resp])   {
    def extract: Resp = value match {
      case SimpleActor.QueryResponse.Ok(result)            => result
      case SimpleActor.QueryResponse.UnexpectedQuery(desc) => throw new IllegalArgumentException(s"Unexpected query: $desc")
    }
  }

}
