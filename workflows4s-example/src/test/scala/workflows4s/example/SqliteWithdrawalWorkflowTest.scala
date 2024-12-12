package workflows4s.example

import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.EventCodec
import workflows4s.example.testuitls.{CirceEventCodec, SqliteSuite}
import workflows4s.example.withdrawal.WithdrawalWorkflow

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec, StrictLogging, SqliteSuite, WithdrawalWorkflowTest.Suite {
  "sqlite" - {
    createSchema(xa).unsafeRunSync()
    withdrawalTests(new TestRuntimeAdapter.Sqlite[WithdrawalWorkflow.Context.Ctx](xa, eventCodec))
  }

  lazy val eventCodec: EventCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()
}
