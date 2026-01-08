package workflows4s.example

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*
import workflows4s.runtime.instanceengine.Effect

class SqliteWithdrawalWorkflowTest extends AnyFreeSpec with SqliteWorkdirSuite with WithdrawalWorkflowTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  "sqlite" - {
    withdrawalTests(new SqliteRuntimeAdapter(workdir, eventCodec))
  }

  lazy val eventCodec: ByteCodec[testContext.Context.Event] = CirceEventCodec.get()
}
