package workflows4s.testing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.testing.matrix.*
import workflows4s.wio.*
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
    workflowTests(InMemorySynchronizedTestRuntimeAdapter[IO, TestCtx2.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))

    "effect matrix" - {
      "IO" - {
        matrixTests(TestCtxIO)(InMemorySynchronizedTestRuntimeAdapter[IO, TestCtxIO.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))
      }
      "Try" - {
        matrixTests(TestCtxTry)(InMemorySynchronizedTestRuntimeAdapter[Try, TestCtxTry.Ctx]([A] => (fa: Try[A]) => fa.get))
      }
      "Either" - {
        matrixTests(TestCtxEither)(
          InMemorySynchronizedTestRuntimeAdapter[EitherThrowable, TestCtxEither.Ctx]([A] => (fa: EitherThrowable[A]) => fa.fold(throw _, identity)),
        )
      }
      "Function0" - {
        matrixTests(TestCtxThunk)(InMemorySynchronizedTestRuntimeAdapter[Function0, TestCtxThunk.Ctx]([A] => (fa: () => A) => fa()))
      }
      "ZIO Task" - {
        matrixTests(TestCtxZIO)(
          InMemorySynchronizedTestRuntimeAdapter[Task, TestCtxZIO.Ctx]([A] =>
            (fa: Task[A]) => Unsafe.unsafe { implicit unsafe => zioRuntime.unsafe.run(fa).getOrThrow() },
          ),
        )
      }
      "Future" - {
        matrixTests(TestCtxFuture)(
          InMemorySynchronizedTestRuntimeAdapter[Future, TestCtxFuture.Ctx]([A] => (fa: Future[A]) => Await.result(fa, Duration.Inf)),
        )
      }
    }
  }

}
