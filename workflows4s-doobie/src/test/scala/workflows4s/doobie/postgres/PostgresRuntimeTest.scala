package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.catseffect.CatsEffect.given
import workflows4s.doobie.DatabaseRuntime
import workflows4s.doobie.postgres.testing.PostgresSuite
import workflows4s.runtime.instanceengine.{BasicJavaTimeEngine, WorkflowInstanceEngine}
import workflows4s.wio.WorkflowContext

import java.time.Clock
import scala.concurrent.duration.DurationInt
import scala.util.Try

// TODO: Restore generic workflow tests once IO-specific test infrastructure is available
// The WorkflowRuntimeTest.Suite was removed as part of cats-effect abstraction from core.
class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite {

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.sleep(1.second) *> IO(Event()))
        .handleEvent((_, _) => State())
        .done

      val storage                            = PostgresWorkflowStorage()(using noopCodec(Event()))
      val engine: WorkflowInstanceEngine[IO] = new BasicJavaTimeEngine[IO](Clock.systemUTC())
      val runtime                            = DatabaseRuntime.create(wio, State(), xa, engine, storage, "workflow")
      val workflowInstance                   = runtime.createInstance("1").unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  object TestCtx extends WorkflowContext {
    case class State()
    case class Event()
    type F[A] = IO[A]
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.ByteCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
