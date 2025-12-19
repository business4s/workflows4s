package workflows4s.example

import org.scalatest.freespec.AnyFreeSpec
import org.scalamock.scalatest.MockFactory
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite with MockFactory with WithdrawalWorkflowTest.Suite {

  "sqlite" - {
    withdrawalTests(new SqliteRuntimeAdapter[WithdrawalWorkflow.Context.Ctx](workdir, eventCodec), skipRecovery = true)
  }

  lazy val eventCodec: ByteCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()

}
