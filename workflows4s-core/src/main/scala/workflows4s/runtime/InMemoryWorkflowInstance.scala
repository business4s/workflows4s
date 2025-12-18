package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.wio.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** In-memory workflow instance that works with any effect type F[_].
  *
  * Uses simple Java synchronization primitives for thread-safety.
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
  private val lock                                 = new Object

  def getEvents: Seq[WCEvent[Ctx]] = lock.synchronized { events.toList }

  def recover(events: Seq[WCEvent[Ctx]]): F[Unit] = {
    E.map(super.recover(wf, events)) { newState =>
      lock.synchronized {
        this.events ++= events
        wf = newState
      }
    }
  }

  override protected def getWorkflow: F[ActiveWorkflow[F, Ctx]] = E.delay {
    lock.synchronized { wf }
  }

  override protected def persistEvent(event: WCEvent[Ctx]): F[Unit] = E.delay {
    lock.synchronized { events += event; () }
  }

  override protected def updateState(newState: ActiveWorkflow[F, Ctx]): F[Unit] = E.delay {
    lock.synchronized { wf = newState }
  }

  override protected def lockState[T](update: ActiveWorkflow[F, Ctx] => F[T]): F[T] = {
    // For synchronous effects (like Id), we need the lock to be held during the entire update
    // For async effects (like IO), the lock protects getting the current state
    lock.synchronized {
      E.flatMap(E.delay(wf))(update)
    }
  }
}
