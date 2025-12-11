package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.DatabaseRuntime
import workflows4s.doobie.postgres.testing.{JavaSerdeEventCodec, PostgresRuntimeAdapter, PostgresSuite}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.wio.{TestCtx2, WorkflowContext}

import scala.concurrent.duration.DurationInt
import scala.util.Try

class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite with WorkflowRuntimeTest.Suite {

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.sleep(1.second) *> IO(Event()))
        .handleEvent((_, _) => State())
        .done

      val storage          = PostgresWorkflowStorage()(using noopCodec(Event()))
      val engine           = WorkflowInstanceEngine.basic()
      val runtime          = DatabaseRuntime.create(wio, State(), xa, engine, storage, "workflow")
      val workflowInstance = runtime.createInstance("1").unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  "generic tests" - {
    workflowTests(new PostgresRuntimeAdapter[TestCtx2.Ctx](xa, JavaSerdeEventCodec.get))
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
