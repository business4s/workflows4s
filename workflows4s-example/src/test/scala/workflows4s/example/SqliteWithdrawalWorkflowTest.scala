package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.SqliteRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, SqliteSuite}
import workflows4s.example.withdrawal.*

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteSuite with WithdrawalWorkflowTest.Suite {

  "sqlite" - {
    lazy val adapter = new SqliteRuntimeAdapter[WithdrawalWorkflow.Context.Ctx](
      dbPath = dbFilePath,
      eventCodec = CirceEventCodec.get(),
    )
    withdrawalTests(adapter)
  }
}
