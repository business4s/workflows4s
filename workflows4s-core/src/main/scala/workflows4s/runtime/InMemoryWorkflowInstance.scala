package workflows4s.runtime

import cats.Monad
import cats.effect.std.AtomicCell
import cats.effect.{IO, LiftIO, Ref}
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.KnockerUpper.Agent.Curried
import workflows4s.wio.*

import java.time.Clock

class InMemoryWorkflowInstance[Ctx <: WorkflowContext](
    stateCell: AtomicCell[IO, ActiveWorkflow[Ctx]],
    eventsRef: Ref[IO, Vector[WCEvent[Ctx]]],
    protected val clock: Clock,
    protected val knockerUpper: KnockerUpper.Agent.Curried,
    protected val registry: WorkflowRegistry.Agent.Curried,
) extends WorkflowInstanceBase[IO, Ctx] {

  def getEvents: IO[Vector[WCEvent[Ctx]]] = eventsRef.get

  def recover(events: Seq[WCEvent[Ctx]]): IO[Unit] =
    stateCell.evalModify { oldState =>
      for {
        newState <- super.recover(oldState, events)
        _        <- eventsRef.update(_ ++ events)
      } yield newState -> ()
    }

  override protected def fMonad: Monad[IO]  = summon
  override protected def liftIO: LiftIO[IO] = summon

  override protected def getWorkflow: IO[ActiveWorkflow[Ctx]] = stateCell.get

  override protected def lockAndUpdateState[T](
      update: ActiveWorkflow[Ctx] => IO[LockOutcome[T]],
  ): IO[StateUpdate[T]] = {
    stateCell.evalModify { oldState =>
      update(oldState).flatMap {
        case LockOutcome.NewEvent(event, result) =>
          for {
            now     <- IO(clock.instant())
            newState = processLiveEvent(event, oldState, now)
            _       <- eventsRef.update(_ :+ event)
          } yield (newState, StateUpdate.Updated(oldState, newState, result))
        case LockOutcome.NoOp(result)            =>
          IO.pure((oldState, StateUpdate.NoOp(oldState, result)))
      }
    }
  }
}
