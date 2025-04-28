package workflows4s.doobie

import cats.Monad
import cats.effect.{LiftIO, Sync}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.WorkflowInstanceBase
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.KnockerUpper.Agent.Curried
import workflows4s.wio.*

import java.time.Clock

class DbWorkflowInstance[Ctx <: WorkflowContext, Id](
    id: Id,
    baseWorkflow: ActiveWorkflow[Ctx],
    storage: WorkflowStorage[Id],
    eventCodec: EventCodec[WCEvent[Ctx]],
    protected val clock: Clock,
    knockerUpperForId: KnockerUpper.Agent[Id],
    protected val liftIO: LiftIO[ConnectionIO],
) extends WorkflowInstanceBase[ConnectionIO, Ctx]
    with StrictLogging {

  override protected def fMonad: Monad[ConnectionIO] = summon
  override protected lazy val knockerUpper: Curried  = knockerUpperForId.curried(id)

  override protected def getWorkflow: ConnectionIO[ActiveWorkflow[Ctx]] = for {
    events   <- queryEvents
    newState <- recover(baseWorkflow, events)
  } yield newState

  private def queryEvents: ConnectionIO[List[WCEvent[Ctx]]] = {
    storage.getEvents(id).flatMap(_.traverse(eventBytes => Sync[ConnectionIO].fromTry(eventCodec.read(eventBytes))))
  }

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => ConnectionIO[LockOutcome[T]]): ConnectionIO[StateUpdate[T]] = {
    for {
      oldState    <- getWorkflow
      lockResult  <- update(oldState)
      now         <- Sync[ConnectionIO].delay(clock.instant())
      stateUpdate <- lockResult match {
                       case LockOutcome.NewEvent(event, result) =>
                         for {
                           _       <- storage.saveEvent(id, eventCodec.write(event))
                           newState = processLiveEvent(event, oldState, now)
                         } yield StateUpdate.Updated(oldState, newState, result)
                       case LockOutcome.NoOp(result)            => StateUpdate.NoOp(oldState, result).pure[ConnectionIO]
                     }
    } yield stateUpdate

  }
}
