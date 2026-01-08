package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** In-memory workflow instance that works with any effect type F[_].
  *
  * Uses effect-polymorphic mutex for thread-safety. Use the companion object's `create` method to construct instances.
  */
class InMemoryWorkflowInstance[F[_], Ctx <: WorkflowContext] private (
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[F, Ctx],
    protected val engine: WorkflowInstanceEngine[F],
    E: Effect[F],
    mutex: E.Mutex,
) extends WorkflowInstanceBase[F, Ctx](using E)
    with StrictLogging {

  private given Effect[F] = E

  private var wf: ActiveWorkflow[F, Ctx]           = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()

  def getEvents: F[Seq[WCEvent[Ctx]]] = E.withLock(mutex)(E.delay(events.toList))

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] = {
    E.withLock(mutex) {
      super.recover(wf, events).map { newState =>
        this.events ++= events
        wf = newState
      }
    }
  }

  // Note: getWorkflow, persistEvent, updateState are only called from within lockState,
  // so they should NOT acquire the mutex (non-reentrant semaphores would deadlock).
  override protected def getWorkflow: F[ActiveWorkflow[F, Ctx]] =
    E.delay(wf)

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] =
    E.delay { events += event; () }

  override protected def updateState(newState: ActiveWorkflow[F, Ctx]): F[Unit] =
    E.delay { wf = newState }

  override protected def lockState[T](update: ActiveWorkflow[F, Ctx] => F[T]): F[T] = {
    E.withLock(mutex) {
      E.delay(wf).flatMap(update)
    }
  }
}

object InMemoryWorkflowInstance {

  /** Create a new InMemoryWorkflowInstance within the effect context.
    */
  def create[F[_], Ctx <: WorkflowContext](
      id: WorkflowInstanceId,
      initialState: ActiveWorkflow[F, Ctx],
      engine: WorkflowInstanceEngine[F],
  )(using E: Effect[F]): F[InMemoryWorkflowInstance[F, Ctx]] = {
    E.createMutex.map { mutex =>
      new InMemoryWorkflowInstance[F, Ctx](id, initialState, engine, E, mutex)
    }
  }
}
