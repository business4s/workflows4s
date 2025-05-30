package workflows4s.runtime.pekko

import cats.Monad
import cats.effect.{IO, LiftIO}
import cats.implicits.{catsSyntaxApplicativeError, catsSyntaxApplicativeId}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import workflows4s.runtime.WorkflowInstanceBase
import workflows4s.runtime.pekko.WorkflowBehavior.{LockExpired, StateLockId}
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.KnockerUpper.Agent.Curried
import workflows4s.wio.{ActiveWorkflow, WorkflowContext}

import java.time.Clock
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class PekkoWorkflowInstance[Ctx <: WorkflowContext](
    actorRef: RecipientRef[WorkflowBehavior.Command[Ctx]],
    protected val knockerUpper: KnockerUpper.Agent.Curried,
    protected val clock: Clock,
    protected val registry: WorkflowRegistry.Agent.Curried,
    stateQueryTimeout: Timeout = Timeout(100.millis),
    lockTimeout: Timeout = Timeout(5.seconds),
)(using system: ActorSystem[?])
    extends WorkflowInstanceBase[Future, Ctx] {

  import system.executionContext

  override protected def fMonad: Monad[Future] = summon

  override protected def liftIO: LiftIO[Future] = new LiftIO[Future] {
    // TODO use dispatcher instead? Maybe not worth if we abstract over Effects (https://github.com/business4s/workflows4s/issues/59)
    import cats.effect.unsafe.implicits.global
    override def liftIO[A](ioa: IO[A]): Future[A] = ioa.unsafeToFuture()
  }

  override protected def getWorkflow: Future[ActiveWorkflow[Ctx]] = {
    given Timeout = stateQueryTimeout
    actorRef.ask[ActiveWorkflow[Ctx]](replyTo => WorkflowBehavior.Command.QueryState(replyTo))
  }

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => Future[LockOutcome[T]]): Future[StateUpdate[T]] = {
    val lockId    = StateLockId.random()
    given Timeout = lockTimeout
    def unlock    = actorRef.ask[Unit](replyTo => WorkflowBehavior.Command.UnlockState(lockId, replyTo))
    for {
      oldState <- actorRef
                    .ask[ActiveWorkflow[Ctx]](replyTo => WorkflowBehavior.Command.LockState(lockId, replyTo))
                    .onError(_ => unlock) // in case of timeout or other problems with receiving response.
      lockResult <- update(oldState).onError(err => {
                      logger.error(s"State update failed. Releasing lock ${lockId}", err)
                      unlock
                    })
      result     <- lockResult match {
                      case LockOutcome.NewEvent(event, result) =>
                        for {
                          askResult <-
                            actorRef.ask[LockExpired.type | ActiveWorkflow[Ctx]](replyTo => WorkflowBehavior.Command.UpdateState(lockId, replyTo, event))
                          result    <- askResult match {
                                         case LockExpired                                   =>
                                           // TODO actually this could happen
                                           //  if the actor gets passivated/killed in the middle of signal processing.
                                           //  If this happens often adn we cant find a better solution, we would have
                                           //  to move the locking state into persistent storage.
                                           throw new Exception(
                                             "Couldn't update the state because the lock has expired. This should never happen. Please report as a bug.",
                                           )
                                         case newState: workflows4s.wio.ActiveWorkflow[Ctx] => StateUpdate.Updated(oldState, newState, result).pure[Future]
                                       }
                        } yield result
                      case LockOutcome.NoOp(result)            =>
                        for {
                          _ <- unlock
                        } yield StateUpdate.NoOp(oldState, result)
                    }

    } yield result
  }
}
