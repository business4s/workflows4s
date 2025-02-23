package workflows4s.doobie.sqlite

import cats.effect.kernel.Resource
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.WorkflowStorage

class SqliteWorkflowStorage[WorkflowId <: String] extends WorkflowStorage[WorkflowId] {
  override def getEvents(id: WorkflowId): ConnectionIO[List[IArray[Byte]]] = {
    sql"SELECT event_data FROM workflow_journal".query[Array[Byte]].map(IArray.unsafeFromArray).to[List]
  }

  override def saveEvent(id: WorkflowId, event: IArray[Byte]): ConnectionIO[Unit] = {
    sql"INSERT INTO workflow_journal (event_data) VALUES (${event.toArray})".update.run.void
  }

  override def lockWorkflow(id: WorkflowId): Resource[ConnectionIO, Unit] = {
    Resource.pure[ConnectionIO, Unit](())
  }
}
