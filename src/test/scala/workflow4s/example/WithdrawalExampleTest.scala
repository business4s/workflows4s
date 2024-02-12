package workflow4s.example

import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import workflow4s.wio.{ActiveWorkflow, Interpreter, QueryResponse}

class WithdrawalExampleTest extends AnyFreeSpec {

  "Withdrawal Example" - {

    "init" in {
      val actor = new WithdrawalActor
      assert(actor.getData() == WithdrawalData.Empty)
      actor.init(CreateWithdrawal(100))
      assert(actor.getData() == WithdrawalData.Initiated(100))
    }
  }

  class WithdrawalActor
      extends SimpleActor[WithdrawalData](
        ActiveWorkflow(WithdrawalData.Empty, WithdrawalExample.workflow, new Interpreter(new InMemoryJournal), ()),
      ) {
    def init(req: CreateWithdrawal): Unit = this.handleSignal(WithdrawalExample.createWithdrawalSignal)(req).extract

    def getData(): WithdrawalData = this.handleQuery(WithdrawalExample.dataQuery)(()).extract
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
