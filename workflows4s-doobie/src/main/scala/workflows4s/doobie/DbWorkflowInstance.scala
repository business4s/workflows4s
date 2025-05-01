package workflows4s.doobie

import cats.Monad
import cats.data.Kleisli
import cats.effect.{IO, LiftIO, Sync}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.WorkflowInstanceBase
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*

import java.time.Clock

private type Result[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

class DbWorkflowInstance[Ctx <: WorkflowContext, Id](
    id: Id,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[Id],
    eventCodec: EventCodec[WCEvent[Ctx]],
    protected val clock: Clock,
    knockerUpperForId: KnockerUpper.Agent[Id],
    registryAgent: WorkflowRegistry.Agent[Id],
) extends WorkflowInstanceBase[Result, Ctx]
    with StrictLogging {

  override protected def fMonad: Monad[Result]                         = summon
  override protected lazy val knockerUpper: KnockerUpper.Agent.Curried = knockerUpperForId.curried(id)
  override protected lazy val registry: WorkflowRegistry.Agent.Curried = registryAgent.curried(id)

  override protected def getWorkflow: Result[ActiveWorkflow[Ctx]] =
    for {
      events   <- queryEvents
      newState <- recover(baseWorkflow, events)
    } yield newState

  private def queryEvents: Result[List[WCEvent[Ctx]]] = Kleisli(liftIo => {
    storage.getEvents(id).flatMap(_.traverse(eventBytes => Sync[ConnectionIO].fromTry(eventCodec.read(eventBytes))))
  })

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => Result[LockOutcome[T]]): Result[StateUpdate[T]] = {
    for {
      oldState    <- getWorkflow
      lockResult  <- update(oldState)
      now         <- Sync[Result].delay(clock.instant())
      stateUpdate <- lockResult match {
                       case LockOutcome.NewEvent(event, result) =>
                         Kleisli(_ =>
                           for {
                             _       <- storage.saveEvent(id, eventCodec.write(event))
                             newState = processLiveEvent(event, oldState, now)
                           } yield StateUpdate.Updated(oldState, newState, result),
                         )
                       case LockOutcome.NoOp(result)            => StateUpdate.NoOp(oldState, result).pure[Result]
                     }
    } yield stateUpdate
  }

  override protected def liftIO: LiftIO[Result] = new LiftIO[Result] {
    override def liftIO[A](ioa: IO[A]): Result[A] = Kleisli(liftIO => liftIO.liftIO(ioa))
  }
}
