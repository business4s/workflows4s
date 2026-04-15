package workflows4s.doobie.sqlite

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.postgres.testing.JavaSerdeEventCodec
import workflows4s.doobie.sqlite.testing.{SqliteRuntimeAdapter, SqliteWorkdirSuite}
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.testing.matrix.*
import workflows4s.wio.TestCtx2
import zio.interop.catz.*

import LiftToIO.given

class SqliteRuntimeTest extends AnyFreeSpec with SqliteWorkdirSuite with WorkflowRuntimeTest.Suite with EffectMatrixTest {

  given zio.Runtime[Any] = zio.Runtime.default

  private val ioRunSync: [A] => IO[A] => A = [A] => (fa: IO[A]) => fa.unsafeRunSync()

  "generic tests" - {
    workflowTests(new SqliteRuntimeAdapter[IO, TestCtx2.Ctx](workdir, JavaSerdeEventCodec.get, ioRunSync))
  }

  "effect matrix" - {
    "IO" - {
      matrixTests(TestCtxIO)(new SqliteRuntimeAdapter[IO, TestCtxIO.Ctx](workdir, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Try" - {
      matrixTests(TestCtxTry)(new SqliteRuntimeAdapter[IO, TestCtxTry.Ctx](workdir, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Either" - {
      matrixTests(TestCtxEither)(new SqliteRuntimeAdapter[IO, TestCtxEither.Ctx](workdir, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Function0" - {
      matrixTests(TestCtxThunk)(new SqliteRuntimeAdapter[IO, TestCtxThunk.Ctx](workdir, JavaSerdeEventCodec.get, ioRunSync))
    }
    "ZIO Task" - {
      val rt = zio.Runtime.default
      matrixTests(TestCtxZIO)(
        new SqliteRuntimeAdapter[zio.Task, TestCtxZIO.Ctx](
          workdir,
          JavaSerdeEventCodec.get,
          [A] => (fa: zio.Task[A]) => zio.Unsafe.unsafe { implicit unsafe => rt.unsafe.run(fa).getOrThrow() },
        ),
      )
    }
  }

}
