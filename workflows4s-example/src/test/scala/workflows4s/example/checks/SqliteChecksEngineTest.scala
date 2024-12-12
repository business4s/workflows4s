package workflows4s.example.checks

import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.EventCodec
import workflows4s.example.TestRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, SqliteSuite}
import workflows4s.example.withdrawal.checks.*
import workflows4s.example.withdrawal.checks.ChecksEngine.Context

class SqliteChecksEngineTest extends AnyFreeSpec, SqliteSuite, ChecksEngineTest.Suite, StrictLogging {
  val eventCodec: EventCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()

  "sqlite" - {
    createSchema(xa).unsafeRunSync()
    checkEngineTests(new TestRuntimeAdapter.Sqlite[ChecksEngine.Context](xa, eventCodec))
  }
}
