package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.DatabaseRuntime
import workflows4s.doobie.postgres.testing.{JavaSerdeEventCodec, PostgresRuntimeAdapter, PostgresSuite}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.testing.matrix.*
import workflows4s.wio.{TestCtx2, WorkflowContext}
import workflows4s.wio.given
import zio.interop.catz.*

import scala.concurrent.duration.DurationInt
import scala.util.Try

import EffectInstances.given
import LiftToIO.given

class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite with WorkflowRuntimeTest.Suite with EffectMatrixTest {

  given zio.Runtime[Any] = zio.Runtime.default

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.sleep(1.second) *> IO(Event()))
        .handleEvent((_, _) => State())
        .done

      val storage          = PostgresWorkflowStorage()(using noopCodec(Event()))
      val engine           = WorkflowInstanceEngine.basic[IO, TestCtx.Ctx]()
      val runtime          = DatabaseRuntime.create(wio, State(), xa, engine, storage, "workflow")
      val workflowInstance = runtime.createInstance("1").unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  private val ioRunSync: [A] => IO[A] => A = [A] => (fa: IO[A]) => fa.unsafeRunSync()

  "generic tests" - {
    workflowTests(new PostgresRuntimeAdapter[IO, TestCtx2.Ctx](xa, JavaSerdeEventCodec.get, ioRunSync))
  }

  "effect matrix" - {
    "IO" - {
      matrixTests(TestCtxIO)(new PostgresRuntimeAdapter[IO, TestCtxIO.Ctx](xa, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Try" - {
      matrixTests(TestCtxTry)(new PostgresRuntimeAdapter[IO, TestCtxTry.Ctx](xa, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Either" - {
      matrixTests(TestCtxEither)(new PostgresRuntimeAdapter[IO, TestCtxEither.Ctx](xa, JavaSerdeEventCodec.get, ioRunSync))
    }
    "Function0" - {
      matrixTests(TestCtxThunk)(new PostgresRuntimeAdapter[IO, TestCtxThunk.Ctx](xa, JavaSerdeEventCodec.get, ioRunSync))
    }
    "ZIO Task" - {
      val rt = zio.Runtime.default
      matrixTests(TestCtxZIO)(new PostgresRuntimeAdapter[zio.Task, TestCtxZIO.Ctx](
        transactorFor[zio.Task],
        JavaSerdeEventCodec.get,
        [A] => (fa: zio.Task[A]) => zio.Unsafe.unsafe { implicit unsafe => rt.unsafe.run(fa).getOrThrow() },
      ))
    }
  }

  object TestCtx extends WorkflowContext {
    type Effect = cats.effect.IO
    case class State()
    case class Event()
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.ByteCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
