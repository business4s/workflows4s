package workflows4s.runtime

import cats.Monad
import cats.effect.std.{AtomicCell, Semaphore}
import cats.effect.{IO, LiftIO, Ref, Resource}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

class InMemoryWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    stateCell: AtomicCell[IO, ActiveWorkflow[Ctx]],
    eventsRef: Ref[IO, Vector[WCEvent[Ctx]]],
    protected val engine: WorkflowInstanceEngine,
    val lock: Semaphore[IO],
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

  override protected def persistEvent(event: WCEvent[Ctx]): IO[Unit] = eventsRef.update(_ :+ event)

  override protected def updateState(newState: ActiveWorkflow[Ctx]): IO[Unit] = stateCell.set(newState)

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => IO[T]): IO[T] =
    Resource.make(lock.acquire)(_ => lock.release).use(_ => stateCell.get.flatMap(update))
}
