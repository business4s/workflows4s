package workflows4s.example

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*
import workflows4s.wio.given

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite with WithdrawalWorkflowTest.Suite {

  "sqlite" - {
    lazy val adapter = new SqliteRuntimeAdapter[IO, WithdrawalWorkflow.Context.Ctx](
      workdir = workdir,
      eventCodec = CirceEventCodec.get(),
      runSyncFn = [A] => (fa: IO[A]) => fa.unsafeRunSync(),
    )
    withdrawalTests(adapter)
  }
}
