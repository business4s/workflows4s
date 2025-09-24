package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import cats.{Monad, ~>}
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

import scala.util.chaining.scalaUtilChainingOps

private type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine,
) extends WorkflowInstanceBase[Result, Ctx]
    with StrictLogging {

  override protected def fMonad: Monad[Result] = summon

  private val connIOToResult: ConnectionIO ~> Result = new FunctionK {
    override def apply[A](fa: ConnectionIO[A]): Result[A] = Kleisli(_ => fa)
  }

  override protected def getWorkflow: Result[ActiveWorkflow[Ctx]] = {
    Kleisli(connLifIo =>
      storage
        .getEvents(id)
        .evalFold(baseWorkflow)((state, event) => engine.processEvent(state, event).to[IO].pipe(connLifIo.liftIO))
        .compile
        .lastOrError,
    )
  }

  override protected def liftIO: LiftIO[Result] = new LiftIO[Result] {
    override def liftIO[A](ioa: IO[A]): Result[A] = Kleisli(liftIO => liftIO.liftIO(ioa))
  }

  override protected def persistEvent(event: WCEvent[Ctx]): Result[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Result[Unit] = fMonad.unit

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Result[T]): Result[T] =
    storage.lockWorkflow(id).mapK(connIOToResult).use(_ => getWorkflow.flatMap(update))
}
