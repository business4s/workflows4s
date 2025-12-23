package workflows4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.checks.{ChecksEngine, ChecksEvent}

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with ChecksEngineTest.Suite {

  "postgres" - {
    // skipRecovery=true: DatabaseRuntime handles recovery internally via event replay from DB.
    // The test's recovery mechanism (getEvents + replay) doesn't apply to database-backed runtimes.
    checkEngineTests(new PostgresRuntimeAdapter[ChecksEngine.Context](xa, eventCodec), skipRecovery = true)
  }

  lazy val eventCodec: ByteCodec[ChecksEvent] = CirceEventCodec.get()

}
