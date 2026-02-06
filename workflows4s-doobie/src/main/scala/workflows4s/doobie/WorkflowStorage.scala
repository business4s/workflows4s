package workflows4s.doobie

import cats.effect.kernel.Resource
import doobie.*
import workflows4s.runtime.WorkflowInstanceId

/** Database-level event storage and locking. Implementations exist for PostgreSQL (advisory locks) and SQLite (database-level locks). */
trait WorkflowStorage[Event] {

  def getEvents(id: WorkflowInstanceId): fs2.Stream[ConnectionIO, Event]
  def saveEvent(id: WorkflowInstanceId, event: Event): ConnectionIO[Unit]

  /** Acquires an exclusive lock for the given workflow instance. The lock is released when the Resource is finalized. This prevents concurrent
    * processing of events for the same instance.
    */
  def lockWorkflow(id: WorkflowInstanceId): Resource[ConnectionIO, Unit]

}
