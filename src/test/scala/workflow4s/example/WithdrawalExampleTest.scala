package workflow4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.WithdrawalExample.{CreateWithdrawal, WithdrawalData}
import workflow4s.wio.{ActiveWorkflow, SignalResponse}
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import cats.effect.unsafe.implicits.global

class WithdrawalExampleTest extends AnyFreeSpec {

  "Withdrawal Example" - {

    "init" - {
      val actor = new WithdrawalActor
      actor.init(CreateWithdrawal(100))
    }
  }

  class WithdrawalActor extends SimpleActor[WithdrawalData](ActiveWorkflow(WithdrawalData(), WithdrawalExample.workflow, new InMemoryJournal)) {
    def init(req: WithdrawalExample.CreateWithdrawal): Unit = this.handleSignal(WithdrawalExample.createWithdrawalSignal)(req).extract
  }

  implicit class SimpleSignalResponseOps[Resp](value: SimpleActor.SignalResponse[Resp]) {
    def extract: Resp = value match {
      case SimpleActor.SignalResponse.Ok(result)       => result
      case SimpleActor.SignalResponse.UnexpectedSignal => throw new IllegalArgumentException("Unexpected signal")
    }
  }

}
