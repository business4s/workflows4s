package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import org.scalamock.scalatest.MockFactory
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.*

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with MockFactory with WithdrawalWorkflowTest.Suite {

  "postgres" - {
    // skipRecovery=true: DatabaseRuntime handles recovery internally via event replay from DB.
    // The test's recovery mechanism (getEvents + replay) doesn't apply to database-backed runtimes.
    withdrawalTests(new PostgresRuntimeAdapter[WithdrawalWorkflow.Context.Ctx](xa, eventCodec), skipRecovery = true)
  }

  lazy val eventCodec: ByteCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()

}
