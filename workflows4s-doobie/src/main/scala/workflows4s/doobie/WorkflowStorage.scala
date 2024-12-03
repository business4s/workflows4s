package workflows4s.doobie

import cats.effect.kernel.{Resource, Sync}
import cats.implicits.toFunctorOps
import doobie.*
import doobie.implicits.*

trait WorkflowStorage[Id] {

  def getEvents(id: Id): ConnectionIO[List[IArray[Byte]]]
  def saveEvent(id: Id, event: IArray[Byte]): ConnectionIO[Unit]

  // Resource because some locking mechanisms might require an explicit release
  def lockWorkflow(id: Id): Resource[ConnectionIO, Unit]

}


