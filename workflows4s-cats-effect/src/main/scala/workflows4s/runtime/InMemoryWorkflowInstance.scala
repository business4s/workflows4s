package workflows4s.runtime

import cats.effect.std.{AtomicCell, Semaphore}
import cats.effect.{IO, Resource}
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

class InMemoryWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    stateCell: AtomicCell[IO, ActiveWorkflow[Ctx]],
    eventsRef: cats.effect.Ref[IO, Vector[WCEvent[Ctx]]],
    protected val engine: WorkflowInstanceEngine[IO],
    val lock: Semaphore[IO],
) extends WorkflowInstanceBase[IO, IO, Ctx] {

  override protected given E: Effect[IO]       = workflows4s.catseffect.CatsEffect.ioEffect
  override protected given EngineE: Effect[IO] = workflows4s.catseffect.CatsEffect.ioEffect

  def getEvents: IO[Vector[WCEvent[Ctx]]] = eventsRef.get

  def recover(events: Seq[WCEvent[Ctx]]): IO[Unit] =
    stateCell.evalModify { oldState =>
      for {
        newState <- super.recover(oldState, events)
        _        <- eventsRef.update(_ ++ events)
      } yield newState -> ()
    }

  override protected def liftEngineEffect[A](fa: IO[A]): IO[A] = fa

  override protected def getWorkflow: IO[ActiveWorkflow[Ctx]] = stateCell.get

  override protected def persistEvent(event: WCEvent[Ctx]): IO[Unit] = eventsRef.update(_ :+ event)

  override protected def updateState(newState: ActiveWorkflow[Ctx]): IO[Unit] = stateCell.set(newState)

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => IO[T]): IO[T] =
    Resource.make(lock.acquire)(_ => lock.release).use(_ => stateCell.get.flatMap(update))
}
