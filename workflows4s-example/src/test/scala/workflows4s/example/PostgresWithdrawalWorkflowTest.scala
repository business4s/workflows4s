package workflows4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.*
import workflows4s.wio.given

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with WithdrawalWorkflowTest.Suite {

  "postgres" - {
    withdrawalTests(new PostgresRuntimeAdapter[IO, WithdrawalWorkflow.Context.Ctx](xa, eventCodec, [A] => (fa: IO[A]) => fa.unsafeRunSync()))
  }

  lazy val eventCodec: ByteCodec[WithdrawalWorkflow.Context.Event] = CirceEventCodec.get()

}
