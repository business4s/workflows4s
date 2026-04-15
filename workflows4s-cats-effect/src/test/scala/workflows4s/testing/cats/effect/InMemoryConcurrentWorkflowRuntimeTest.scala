package workflows4s.testing.cats.effect

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.testing.matrix.*
import workflows4s.wio.*
import zio.*
import zio.interop.catz.*

class InMemoryConcurrentWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite with EffectMatrixTest {

  private val zioRuntime = zio.Runtime.default

  "in-memory-concurrent" - {
    workflowTests(InMemoryConcurrentTestRuntimeAdapter[IO, TestCtx2.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))

    "effect matrix" - {
      "IO" - {
        matrixTests(TestCtxIO)(InMemoryConcurrentTestRuntimeAdapter[IO, TestCtxIO.Ctx]([A] => (fa: IO[A]) => fa.unsafeRunSync()))
      }
      "ZIO Task" - {
        matrixTests(TestCtxZIO)(
          InMemoryConcurrentTestRuntimeAdapter[Task, TestCtxZIO.Ctx]([A] =>
            (fa: Task[A]) => Unsafe.unsafe { implicit unsafe => zioRuntime.unsafe.run(fa).getOrThrow() },
          ),
        )
      }
    }
  }

}
