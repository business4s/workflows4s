package workflows4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.checks.{ChecksEngine, ChecksEvent}

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new PostgresRuntimeAdapter[ChecksEngine.Context](xa, eventCodec), skipRecovery = true)
  }

  lazy val eventCodec: ByteCodec[ChecksEvent] = CirceEventCodec.get()

}
