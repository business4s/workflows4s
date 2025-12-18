package workflows4s.doobie.postgres

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.doobie.{DatabaseRuntime, Result, ResultWorkflowContext, resultEffect}
import workflows4s.doobie.postgres.testing.{JavaSerdeEventCodec, PostgresRuntimeAdapter, PostgresSuite}
import workflows4s.doobie.testing.{ResultTestCtx2, ResultWorkflowRuntimeTest}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

import scala.util.Try

class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite with ResultWorkflowRuntimeTest.Suite {

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      // Use Result effect type for doobie internal operations
      val wio: WIO.Initial = WIO
        .runIO[Any](_ => resultEffect.delay(Thread.sleep(100)) *> resultEffect.pure(Event()))
        .handleEvent((_, _) => State())
        .done

      val storage          = PostgresWorkflowStorage()(using noopCodec(Event()))
      val engine           = WorkflowInstanceEngine.basic[Result]()
      val runtime          = DatabaseRuntime.create[TestCtx.Ctx](wio, State(), xa, engine, storage, "workflow")
      val workflowInstance = runtime.createInstance("1").unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  "generic tests" - {
    resultWorkflowTests(new PostgresRuntimeAdapter[ResultTestCtx2.Ctx](xa, JavaSerdeEventCodec.get))
  }

  object TestCtx extends ResultWorkflowContext {
    case class State()
    case class Event()
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.ByteCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
