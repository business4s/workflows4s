package workflows4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.example.testuitls.CirceEventCodec
import workflows4s.example.withdrawal.*
import workflows4s.example.withdrawal.checks.ChecksEngine

class SqliteChecksEngineTest extends AnyFreeSpec with SqliteWorkdirSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new SqliteRuntimeAdapter[ChecksEngine.Context](workdir, eventCodec))
  }

  lazy val eventCodec: ByteCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()
}
