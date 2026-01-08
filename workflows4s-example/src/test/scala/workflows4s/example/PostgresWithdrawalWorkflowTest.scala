package workflows4s.example

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.*
import workflows4s.runtime.instanceengine.Effect

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec with PostgresSuite with WithdrawalWorkflowTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  "postgres" - {
    withdrawalTests(new PostgresRuntimeAdapter[testContext.Context.Ctx](xa, eventCodec))
  }

  lazy val eventCodec: ByteCodec[testContext.Context.Event] = CirceEventCodec.get()
}
