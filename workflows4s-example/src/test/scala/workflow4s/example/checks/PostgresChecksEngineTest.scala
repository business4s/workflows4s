package workflow4s.example.checks

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestRuntimeAdapter
import workflow4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflow4s.example.withdrawal.checks.*
import workflow4s.example.withdrawal.checks.ChecksEngine.Context
import workflows4s.doobie.EventCodec

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new TestRuntimeAdapter.Postgres[ChecksEngine.Context](xa, eventCodec))
  }

  lazy val eventCodec: EventCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()

}
