package workflows4s.runtime.cats.effect

import _root_.cats.effect.std.{AtomicCell, Semaphore}
import _root_.cats.effect.{Ref, Resource, Sync}
import _root_.cats.syntax.all.*
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

class InMemoryConcurrentWorkflowInstance[F[_]: Sync, Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    stateCell: AtomicCell[F, ActiveWorkflow[F, Ctx]],
    eventsRef: Ref[F, Vector[WCEvent[Ctx]]],
    protected val engine: WorkflowInstanceEngine[F],
    val lock: Semaphore[F],
) extends WorkflowInstanceBase[F, F, Ctx] {

  def getEvents: F[Vector[WCEvent[Ctx]]] = eventsRef.get

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] =
    stateCell.evalModify { oldState =>
      for {
        newState <- super.recover(oldState, events)
        _        <- eventsRef.update(_ ++ events)
      } yield newState -> ()
    }

  override protected def fMonad: _root_.cats.Monad[F]      = Sync[F]
  override protected def liftG: [A] => F[A] => F[A] = [A] => (fa: F[A]) => fa

  override protected def getWorkflow: F[ActiveWorkflow[F, Ctx]] = stateCell.get

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] = eventsRef.update(_ :+ event)

  override protected def updateState(newState: ActiveWorkflow[F, Ctx]): F[Unit] = stateCell.set(newState)

  override protected def lockState[T](update: ActiveWorkflow[F, Ctx] => F[T]): F[T] =
    Resource.make(lock.acquire)(_ => lock.release).use(_ => stateCell.get.flatMap(update))
}
