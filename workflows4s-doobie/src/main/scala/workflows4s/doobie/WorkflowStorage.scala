package workflows4s.doobie

import cats.effect.kernel.Resource
import doobie.*
import workflows4s.runtime.WorkflowInstanceId

trait WorkflowStorage[Event] {

  def getEvents(id: WorkflowInstanceId): fs2.Stream[ConnectionIO, Event]
  def saveEvent(id: WorkflowInstanceId, event: Event): ConnectionIO[Unit]

  // Resource because some locking mechanisms might require an explicit release
  def lockWorkflow(id: WorkflowInstanceId): Resource[ConnectionIO, Unit]

}
