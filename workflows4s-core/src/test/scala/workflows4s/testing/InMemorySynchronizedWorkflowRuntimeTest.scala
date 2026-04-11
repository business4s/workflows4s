package workflows4s.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.testing.matrix.*
import workflows4s.wio.*
import workflows4s.wio.given
import zio.*
import zio.interop.catz.*

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

import EffectInstances.given

class InMemorySynchronizedWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite with EffectMatrixTest {

  private type EitherThrowable[A] = Either[Throwable, A]
  private val zioRuntime = zio.Runtime.default

  "in-memory-synchronized" - {
    workflowTests(TestRuntimeAdapter.InMemorySync[IO, TestCtx2.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))

    "effect matrix" - {
      "IO" - {
        matrixTests(TestCtxIO)(TestRuntimeAdapter.InMemorySync[IO, TestCtxIO.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))
      }
      "Try" - {
        matrixTests(TestCtxTry)(TestRuntimeAdapter.InMemorySync[Try, TestCtxTry.Ctx]([A] => (fa: Try[A]) => fa.get))
      }
      "Either" - {
        matrixTests(TestCtxEither)(
          TestRuntimeAdapter.InMemorySync[EitherThrowable, TestCtxEither.Ctx]([A] => (fa: EitherThrowable[A]) => fa.fold(throw _, identity)),
        )
      }
      "Function0" - {
        matrixTests(TestCtxThunk)(TestRuntimeAdapter.InMemorySync[Function0, TestCtxThunk.Ctx]([A] => (fa: () => A) => fa()))
      }
      "ZIO Task" - {
        matrixTests(TestCtxZIO)(
          TestRuntimeAdapter.InMemorySync[Task, TestCtxZIO.Ctx]([A] =>
            (fa: Task[A]) => Unsafe.unsafe { implicit unsafe => zioRuntime.unsafe.run(fa).getOrThrow() },
          ),
        )
      }
      "Future" - {
        matrixTests(TestCtxFuture)(
          TestRuntimeAdapter.InMemorySync[Future, TestCtxFuture.Ctx]([A] => (fa: Future[A]) => Await.result(fa, Duration.Inf)),
        )
      }
    }
  }

}
