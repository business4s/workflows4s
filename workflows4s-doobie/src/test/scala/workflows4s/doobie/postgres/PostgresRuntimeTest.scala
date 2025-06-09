package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.DatabaseRuntime
import workflows4s.doobie.postgres.testing.{JavaSerdeEventCodec, PostgresRuntimeAdapter, PostgresSuite}
import workflows4s.runtime.wakeup.NoOpKnockerUpper
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
      val runtime          = DatabaseRuntime.default(wio, State(), xa, NoOpKnockerUpper.Agent, storage)
      val workflowInstance = runtime.createInstance(WorkflowId(1)).unsafeRunSync()

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
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.ByteCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
