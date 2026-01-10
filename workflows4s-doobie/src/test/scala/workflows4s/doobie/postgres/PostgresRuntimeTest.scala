package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect.given
import workflows4s.cats.IOWorkflowContext
import workflows4s.doobie.DatabaseRuntime
import workflows4s.doobie.postgres.testing.{JavaSerdeEventCodec, PostgresRuntimeAdapter, PostgresSuite}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.IOTestCtx

import scala.util.Try

class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite {

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.delay(Thread.sleep(100)) *> IO.pure(Event()))
        .handleEvent((_, _) => State())
        .done

      val engine           = WorkflowInstanceEngine.basic[IO]()
      val runtime          = DatabaseRuntime.create[TestCtx.Ctx](wio, State(), xa, engine, noopCodec(Event()), "workflow")
      val workflowInstance = runtime.createInstance("1").unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  "generic tests" - {
    "runtime should work for basic workflow operations" in {
      import IOTestCtx.*
      val adapter = new PostgresRuntimeAdapter[IOTestCtx.Ctx](xa, JavaSerdeEventCodec.get)

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.pure(SimpleEvent("test")))
        .handleEvent((_, _) => "done")
        .done

      val actor = adapter.runWorkflow(wio.provideInput("initial"), "initial")
      actor.wakeup().unsafeRunSync()

      assert(actor.queryState().unsafeRunSync() == "done")
    }
  }

  object TestCtx extends IOWorkflowContext {
    case class State()
    case class Event()
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.ByteCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
