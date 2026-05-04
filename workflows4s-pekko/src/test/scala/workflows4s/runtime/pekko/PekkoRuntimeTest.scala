package workflows4s.runtime.pekko

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.testing.matrix.*
import workflows4s.wio.TestCtx2
import zio.interop.catz.*

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Try

import EffectInstances.given

class PekkoRuntimeTest
    extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster"))
    with AnyFreeSpecLike
    with WorkflowRuntimeTest.Suite
    with EffectMatrixTest {

  given zio.Runtime[Any] = zio.Runtime.default

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "generic tests" - {
    workflowTests(
      new PekkoRuntimeAdapter[IO, TestCtx2.Ctx](
        "generic-test-workflow",
        [A] => (fa: IO[A]) => fa.unsafeToFuture(),
      ),
    )
  }

  "effect matrix" - {
    "IO" - {
      matrixTests(TestCtxIO)(
        new PekkoRuntimeAdapter[IO, TestCtxIO.Ctx](
          "matrix-io",
          [A] => (fa: IO[A]) => fa.unsafeToFuture(),
        ),
      )
    }
    "Try" - {
      matrixTests(TestCtxTry)(
        new PekkoRuntimeAdapter[Try, TestCtxTry.Ctx](
          "matrix-try",
          [A] => (fa: Try[A]) => Future.fromTry(fa),
        ),
      )
    }
    "Either" - {
      matrixTests(TestCtxEither)(
        new PekkoRuntimeAdapter[[A] =>> Either[Throwable, A], TestCtxEither.Ctx](
          "matrix-either",
          [A] => (fa: Either[Throwable, A]) => Future.fromTry(fa.toTry),
        ),
      )
    }
    "Function0" - {
      matrixTests(TestCtxThunk)(
        new PekkoRuntimeAdapter[Function0, TestCtxThunk.Ctx](
          "matrix-thunk",
          [A] => (fa: () => A) => Future(fa())(using scala.concurrent.ExecutionContext.global),
        ),
      )
    }
    "ZIO Task" - {
      val rt = zio.Runtime.default
      matrixTests(TestCtxZIO)(
        new PekkoRuntimeAdapter[zio.Task, TestCtxZIO.Ctx](
          "matrix-zio",
          [A] => (fa: zio.Task[A]) => zio.Unsafe.unsafe { implicit unsafe => rt.unsafe.runToFuture(fa) },
        ),
      )
    }
    "Future" - {
      matrixTests(TestCtxFuture)(
        new PekkoRuntimeAdapter[Future, TestCtxFuture.Ctx](
          "matrix-future",
          [A] => (fa: Future[A]) => fa,
        ),
      )
    }
  }

}
