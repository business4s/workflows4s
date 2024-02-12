package workflow4s.example

import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.wio.simple.SimpleActor.EventResponse
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import workflow4s.wio.{ActiveWorkflow, Interpreter, QueryResponse}

class WithdrawalExampleTest extends AnyFreeSpec {

  "Withdrawal Example" - {

    "init" in new Fixture {
      assert(actor.queryData() == WithdrawalData.Empty)
      println(actor.wf.wio)
      actor.init(CreateWithdrawal(100))
      assert(actor.queryData() == WithdrawalData.Initiated(100))
      actor.init(CreateWithdrawal(100))
      assert(actor.queryData() == WithdrawalData.Initiated(200))
      checkRecovery()
    }
  }

  trait Fixture {
    val journal = new InMemoryJournal
    val actor   = createActor(journal)

    def checkRecovery() = {
      val secondActor = createActor(journal)
      assert(actor.queryData() == secondActor.queryData())
    }
  }

  def createActor(journal: InMemoryJournal) = {
    val actor = new WithdrawalActor(journal)
    actor.recover()
    actor
  }

  class WithdrawalActor(journal: InMemoryJournal)
      extends SimpleActor[WithdrawalData](
        ActiveWorkflow(WithdrawalData.Empty, WithdrawalExample.workflow, new Interpreter(journal), ()),
      ) {
    def init(req: CreateWithdrawal): Unit = this.handleSignal(WithdrawalExample.createWithdrawalSignal)(req).extract

    def queryData(): WithdrawalData = this.handleQuery(WithdrawalExample.dataQuery)(()).extract

    def recover(): Unit = journal.getEvents.foreach(e =>
      this.handleEvent(e) match {
        case EventResponse.Ok              => ()
        case EventResponse.UnexpectedEvent => throw new IllegalArgumentException(s"Unexpected event :${e}")
      },
    )
  }

  implicit class SimpleSignalResponseOps[Resp](value: SimpleActor.SignalResponse[Resp]) {
    def extract: Resp = value match {
      case SimpleActor.SignalResponse.Ok(result)       => result
      case SimpleActor.SignalResponse.UnexpectedSignal => throw new IllegalArgumentException("Unexpected signal")
    }
  }
  implicit class SimpleQueryResponseOps[Resp](value: QueryResponse[Resp])               {
    def extract: Resp = value match {
      case QueryResponse.Ok(result)        => result
      case QueryResponse.UnexpectedQuery() => throw new IllegalArgumentException("Unexpected query")
    }
  }

}
