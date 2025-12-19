package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.wio.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** In-memory workflow instance that works with any effect type F[_].
  *
  * Uses effect-polymorphic mutex for thread-safety.
  */
class InMemoryWorkflowInstance[F[_], Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    initialState: ActiveWorkflow[F, Ctx],
    protected val engine: WorkflowInstanceEngine[F],
)(using E: Effect[F])
    extends WorkflowInstanceBase[F, Ctx]
    with StrictLogging {

  private var wf: ActiveWorkflow[F, Ctx]           = initialState
  private val events: mutable.Buffer[WCEvent[Ctx]] = ListBuffer[WCEvent[Ctx]]()
  private val mutex: E.Mutex                       = E.createMutex

  def getEvents: F[Seq[WCEvent[Ctx]]] = E.withLock(mutex)(E.delay(events.toList))

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] = {
    E.withLock(mutex) {
      E.map(super.recover(wf, events)) { newState =>
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
      E.flatMap(E.delay(wf))(update)
    }
  }
}
