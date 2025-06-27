package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.SqliteRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, SqliteWorkdirSuite}
import workflows4s.example.withdrawal.*

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite with WithdrawalWorkflowTest.Suite {

  "sqlite" - {
    lazy val adapter = new SqliteRuntimeAdapter[WithdrawalWorkflow.Context.Ctx](
      workdir = workdir,
      eventCodec = CirceEventCodec.get(),
    )
    withdrawalTests(adapter)
  }
}
