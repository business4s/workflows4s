package workflows4s.runtime

import cats.Monad
import cats.effect.{IO, LiftIO, Ref}
import cats.implicits.catsSyntaxApplicativeId
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.KnockerUpper.Agent.Curried
import workflows4s.wio.*

import java.time.Clock

// WARNING: current implementation is not safe in a concurrent scenario.
// See https://github.com/business4s/workflows4s/issues/60 for details.
class InMemoryWorkflowInstance[Ctx <: WorkflowContext](
    stateRef: Ref[IO, ActiveWorkflow[Ctx]],
    eventsRef: Ref[IO, Vector[WCEvent[Ctx]]],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent.Curried,
    protected val registry: WorkflowRegistry.Agent.Curried,
) extends WorkflowInstanceBase[IO, Ctx] {

  def getEvents: IO[Vector[WCEvent[Ctx]]]          = eventsRef.get
  def recover(events: Seq[WCEvent[Ctx]]): IO[Unit] = for {
    oldState <- stateRef.get
    newState <- super.recover(oldState, events)
    _        <- stateRef.set(newState)
    _        <- eventsRef.update(_ ++ events)
  } yield ()

  override protected def fMonad: Monad[IO]  = summon
  override protected def liftIO: LiftIO[IO] = summon

  override protected def getWorkflow: IO[ActiveWorkflow[Ctx]] = stateRef.get

  override protected def lockAndUpdateState[T](update: ActiveWorkflow[Ctx] => IO[LockOutcome[T]]): IO[StateUpdate[T]] = {
    for {
      oldState    <- stateRef.get
      stateUpdate <- update(oldState)
      now         <- IO(clock.instant())
      result      <- stateUpdate match {
                       case LockOutcome.NewEvent(event, result) =>
                         val newState = processLiveEvent(event, oldState, now)
                         for {
                           _ <- stateRef.set(newState)
                           _ <- eventsRef.update(_ :+ event)
                         } yield StateUpdate.Updated(oldState, newState, result)
                       case LockOutcome.NoOp(result)            => StateUpdate.NoOp(oldState, result).pure[IO]
                     }
    } yield result
  }
}
