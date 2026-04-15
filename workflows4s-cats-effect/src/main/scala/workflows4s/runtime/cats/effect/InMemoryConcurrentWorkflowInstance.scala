package workflows4s.runtime.cats.effect

import _root_.cats.effect.std.Semaphore
import _root_.cats.effect.{Ref, Resource, Sync}
import _root_.cats.syntax.all.*
import workflows4s.runtime.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.*

class InMemoryConcurrentWorkflowInstance[F[_]: Sync, Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    stateRef: Ref[F, ActiveWorkflow[Ctx]],
    eventsRef: Ref[F, Vector[WCEvent[Ctx]]],
    protected val engine: WorkflowInstanceEngine[F, Ctx],
    val lock: Semaphore[F],
) extends WorkflowInstanceBase[F, Ctx] {

  def getEvents: F[Vector[WCEvent[Ctx]]] = eventsRef.get

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] =
    lockState { currentWf =>
      for {
        newState <- super.recover(currentWf, events)
        _        <- eventsRef.update(_ ++ events)
        _        <- stateRef.set(newState)
      } yield ()
    }

  override protected def fMonad: _root_.cats.Monad[F] = Sync[F]

  override protected def getWorkflow: F[ActiveWorkflow[Ctx]] = stateRef.get

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] = eventsRef.update(_ :+ event)

  override protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit] = stateRef.set(newState)

  override protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T] =
    Resource.make(lock.acquire)(_ => lock.release).use(_ => stateRef.get.flatMap(update))
}
