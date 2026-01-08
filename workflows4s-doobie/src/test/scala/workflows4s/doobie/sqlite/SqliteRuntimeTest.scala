package workflows4s.doobie.sqlite

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.postgres.testing.JavaSerdeEventCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.wio.IOTestCtx
import cats.effect.unsafe.implicits.global

class SqliteRuntimeTest extends AnyFreeSpec with SqliteWorkdirSuite {

  "generic tests" - {
    "runtime should work for basic workflow operations" in {
      import IOTestCtx.*
      val adapter = new SqliteRuntimeAdapter[IOTestCtx.Ctx](workdir, JavaSerdeEventCodec.get)

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.pure(SimpleEvent("test")))
        .handleEvent((_, _) => "done")
        .done

      val actor = adapter.runWorkflow(wio.provideInput("initial"), "initial")
      actor.wakeup()

      assert(actor.queryState().unsafeRunSync() == "done")
    }
  }

}
