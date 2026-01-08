package workflows4s.example.checks

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.Effect

class SqliteChecksEngineTest extends AnyFreeSpec with SqliteWorkdirSuite with ChecksEngineTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override def createTrackingCheck(pendingCount: Int): Check[IO, Unit] & { def runNum: Int } =
    new Check[IO, Unit] {
      var runNum = 0

      override def key: CheckKey = CheckKey("foo")

      override def run(data: Unit): IO[CheckResult] = runNum match {
        case n if n < pendingCount =>
          IO {
            runNum += 1
          }.as(CheckResult.Pending())

        case _ => IO(CheckResult.Approved())
      }
    }

  "sqlite" - {
    checkEngineTests(new SqliteRuntimeAdapter[testContext.Context.Ctx](workdir, eventCodec))
  }

  lazy val eventCodec: ByteCodec[testContext.Context.Event] = CirceEventCodec.get()
}
