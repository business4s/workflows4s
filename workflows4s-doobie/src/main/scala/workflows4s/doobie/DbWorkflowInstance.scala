package workflows4s.doobie

import cats.{Monad, ~>}
import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO, Sync}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*

import java.time.{Clock, Instant}
import scala.util.chaining.scalaUtilChainingOps

private type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent,
    protected val registry: WorkflowRegistry.Agent,
) extends WorkflowInstanceBase[Result, Ctx]
    with StrictLogging {

  override protected def fMonad: Monad[Result]                         = summon

  private val connIOToResult: ConnectionIO ~> Result = new FunctionK {
    override def apply[A](fa: ConnectionIO[A]): Result[A] = Kleisli(_ => fa)
  }

  override protected def getWorkflow: Result[ActiveWorkflow[Ctx]] = {
    def recoveredState(now: Instant): ConnectionIO[ActiveWorkflow[Ctx]] =
      storage
        .getEvents(id)
        .compile
        .fold(baseWorkflow)((state, event) =>
          state.handleEvent(event, now) match {
            case Some(value) => value
            case None        =>
              logger.warn(s"Ignored event ${}")
              state
          },
        )
    for {
      now    <- currentTime
      result <- recoveredState(now).pipe(connIOToResult.apply)
    } yield result
  }

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => Result[LockOutcome[T]]): Result[StateUpdate[T]] = {
    storage.lockWorkflow(id).mapK(connIOToResult).use { _ =>
      for {
        oldState    <- getWorkflow
        lockResult  <- update(oldState)
        now         <- Sync[Result].delay(clock.instant())
        stateUpdate <- lockResult match {
                         case LockOutcome.NewEvent(event, result) =>
                           Kleisli(_ =>
                             for {
                               _       <- storage.saveEvent(id, event)
                               newState = processLiveEvent(event, oldState, now)
                             } yield StateUpdate.Updated(oldState, newState, result),
                           )
                         case LockOutcome.NoOp(result)            => StateUpdate.NoOp(oldState, result).pure[Result]
                       }
      } yield stateUpdate
    }
  }

  override protected def liftIO: LiftIO[Result] = new LiftIO[Result] {
    override def liftIO[A](ioa: IO[A]): Result[A] = Kleisli(liftIO => liftIO.liftIO(ioa))
  }
}
