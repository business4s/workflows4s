package workflows4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.postgres.testing.PostgresRuntimeAdapter
import workflows4s.example.testuitls.{CirceEventCodec, PostgresSuite}
import workflows4s.example.withdrawal.checks.*

class PostgresChecksEngineTest extends AnyFreeSpec with PostgresSuite with ChecksEngineTest.Suite {

  "postgres" - {
    checkEngineTests(new PostgresRuntimeAdapter[IO, ChecksEngine.Context.Ctx](xa, eventCodec, [A] => (fa: IO[A]) => fa.unsafeRunSync()))
  }

  lazy val eventCodec: ByteCodec[ChecksEngine.Context.Event] = CirceEventCodec.get()

}
