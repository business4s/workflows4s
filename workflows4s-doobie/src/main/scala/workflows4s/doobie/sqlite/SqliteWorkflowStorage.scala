package workflows4s.doobie.sqlite

import cats.effect.kernel.Resource
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.WorkflowStorage

class SqliteWorkflowStorage extends WorkflowStorage[String] {
  override def getEvents(id: String): ConnectionIO[List[IArray[Byte]]] = {
    sql"SELECT event_data FROM workflow_journal".query[Array[Byte]].map(IArray.unsafeFromArray).to[List]
  }

  override def saveEvent(id: String, event: IArray[Byte]): ConnectionIO[Unit] = {
    sql"INSERT INTO workflow_journal (event_data) VALUES (${event.toArray})".update.run.void
  }

  override def lockWorkflow(id: String): Resource[ConnectionIO, Unit] = {
    Resource.pure[ConnectionIO, Unit](())
  }
}
