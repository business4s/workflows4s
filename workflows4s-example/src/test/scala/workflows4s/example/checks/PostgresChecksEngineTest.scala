package workflows4s.example.checks

import cats.effect.IO
import cats.implicits.toTraverseOps
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.scalatest.Suite
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.TestRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.checks.*
import workflows4s.example.withdrawal.checks.ChecksEngine.Context
import workflows4s.doobie.EventCodec

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new TestRuntimeAdapter.Postgres[ChecksEngine.Context](xa, eventCodec))
  }

  lazy val eventCodec: EventCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()

}
