package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.{Monad, ~>}
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

class DbWorkflowInstance[F[_], Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    fEngine: WorkflowInstanceEngine[F, Ctx],
) extends WorkflowInstanceBase[[T] =>> Kleisli[ConnectionIO, FunctionK[F, ConnectionIO], T], Ctx]
    with StrictLogging {

  private type Res[T] = Kleisli[ConnectionIO, FunctionK[F, ConnectionIO], T]

  override protected def fMonad: Monad[Res] = summon

  override protected val engine: WorkflowInstanceEngine[Res, Ctx] =
    fEngine.mapK([A] => (fa: F[A]) => Kleisli[ConnectionIO, FunctionK[F, ConnectionIO], A](fk => fk(fa)))

  private val connIOToResult: ConnectionIO ~> Res = new FunctionK[ConnectionIO, Res] {
    override def apply[A](fa: ConnectionIO[A]): Res[A] = Kleisli(_ => fa)
  }

  override protected def getWorkflow: Res[ActiveWorkflow[Ctx]] = {
    Kleisli(_ =>
      storage
        .getEvents(id)
        .fold(baseWorkflow)((state, event) => engine.processEvent(state, event).unsafeRun())
        .compile
        .lastOrError,
    )
  }

  override protected def persistEvent(event: WCEvent[Ctx]): Res[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  override protected def updateState(newState: ActiveWorkflow[Ctx]): Res[Unit] = fMonad.unit

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => Res[T]): Res[T] =
    storage.lockWorkflow(id).mapK(connIOToResult).use(_ => getWorkflow.flatMap(update))
}
