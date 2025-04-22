package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.wio
import workflows4s.wio.WorkflowContext

import scala.concurrent.duration.DurationInt
import scala.util.Try

class PostgresRuntimeTest extends AnyFreeSpec with PostgresSuite {

  "DbWorkflowInstance" - {
    "should work for long-running IO" in {
      import TestCtx.*

      val wio: WIO.Initial = WIO
        .runIO[Any](_ => IO.sleep(1.second) *> IO(Event()))
        .handleEvent((_, _) => State())
        .done

      val runtime          = PostgresRuntime.default(wio, State(), noopCodec(Event()), xa, NoOpKnockerUpper.Agent)
      val workflowInstance = runtime.createInstance(WorkflowId(1)).unsafeRunSync()

      // this used to throw due to leaked LiftIO
      workflowInstance.wakeup().unsafeRunSync()
    }
  }

  object TestCtx extends WorkflowContext {
    case class State()
    case class Event()
  }

  def noopCodec[E](evt: E) = new workflows4s.doobie.EventCodec[E] {
    override def write(event: E): IArray[Byte]     = IArray.empty
    override def read(bytes: IArray[Byte]): Try[E] = scala.util.Success(evt)
  }
}
