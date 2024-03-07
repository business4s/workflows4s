package workflow4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.example.WithdrawalService.Fee
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.wio.model.WIOModelInterpreter
import workflow4s.wio.simple.SimpleActor.EventResponse
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import workflow4s.wio.{ActiveWorkflow, Interpreter}

import java.io.File

class WithdrawalWorkflowTest extends AnyFreeSpec {

  "Withdrawal Example" - {

    "init" in new Fixture {
      assert(actor.queryData() == WithdrawalData.Empty(txId))
      actor.init(CreateWithdrawal(100))
      assert(actor.queryData() == WithdrawalData.Validated(txId, 100, fees))

      checkRecovery()
    }

    "render model" in new Fixture {
      val model     = WIOModelInterpreter.run(new WithdrawalWorkflow(service).workflow)
      import io.circe.syntax._
      val modelJson = model.asJson
      print(modelJson.spaces2)
    }

    "render bpmn model" in new Fixture {
      val model         = WIOModelInterpreter.run(new WithdrawalWorkflow(service).workflow)
      val bpmnModel     = BPMNConverter.convert(model, "withdrawal-example")
      Bpmn.writeModelToFile(new File("src/test/resources/withdrawal-example-bpmn.bpmn"), bpmnModel)
      val modelDecl     = WIOModelInterpreter.run(new WithdrawalWorkflow(service).workflowDeclarative)
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
  }

  def createActor(journal: InMemoryJournal) = {
    val actor = new WithdrawalActor(journal)
    actor.recover()
    actor
  }

  val txId    = "abc"
  val fees    = Fee(11)
  val service = new WithdrawalService {
    override def calculateFees(amount: BigDecimal): IO[Fee] = IO(fees)

    override def putMoneyOnHold(amount: BigDecimal): IO[Either[WithdrawalService.NotEnoughFunds, Unit]] = IO(Right(()))
  }

  class WithdrawalActor(journal: InMemoryJournal)
      extends SimpleActor(
        ActiveWorkflow(new WithdrawalWorkflow(service).workflow, new Interpreter(journal), (WithdrawalData.Empty(txId), ()).asRight),
      ) {
    def init(req: CreateWithdrawal): Unit = {
      this.handleSignal(WithdrawalWorkflow.createWithdrawalSignal)(req).extract
    }

    def queryData(): WithdrawalData = this.handleQuery(WithdrawalWorkflow.dataQuery)(()).extract

    def recover(): Unit = journal.getEvents.foreach(e =>
      this.handleEvent(e) match {
        case EventResponse.Ok                    => ()
        case EventResponse.UnexpectedEvent(desc) => throw new IllegalArgumentException(s"Unexpected event :${desc}")
      },
    )
    this.proceed()
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
