package workflow4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflow4s.example.withdrawal.*
import workflows4s.doobie.EventCodec

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with WithdrawalWorkflowTest.Suite {

  "postgres" - {
    withdrawalTests(new TestRuntimeAdapter.Postgres[WithdrawalWorkflow.Context.Ctx](xa, eventCodec))
  }

  lazy val eventCodec: EventCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()

}
