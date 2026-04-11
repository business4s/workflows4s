package workflows4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.wio.given

class SqliteChecksEngineTest extends AnyFreeSpec with SqliteWorkdirSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new SqliteRuntimeAdapter[IO, ChecksEngine.Context.Ctx](workdir, eventCodec, [A] => (fa: IO[A]) => fa.unsafeRunSync()))
  }

  lazy val eventCodec: ByteCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()
}
