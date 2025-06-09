package workflows4s.doobie

import cats.effect.kernel.Resource
import doobie.*

trait WorkflowStorage[Id, Event] {

  def getEvents(id: Id): fs2.Stream[ConnectionIO, Event]
  def saveEvent(id: Id, event: Event): ConnectionIO[Unit]

  // Resource because some locking mechanisms might require an explicit release
  def lockWorkflow(id: Id): Resource[ConnectionIO, Unit]

}
