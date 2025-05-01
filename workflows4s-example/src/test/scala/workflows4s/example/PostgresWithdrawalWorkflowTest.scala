package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.*
import workflows4s.doobie.ByteCodec

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with WithdrawalWorkflowTest.Suite {

  "postgres" - {
    withdrawalTests(new TestRuntimeAdapter.Postgres[WithdrawalWorkflow.Context.Ctx](xa, eventCodec))
  }

  lazy val eventCodec: ByteCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()

}
