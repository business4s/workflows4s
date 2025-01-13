package workflows4s.doobie.sqlite

import cats.effect.kernel.Resource
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.WorkflowStorage

object SqliteWorkflowStorage extends WorkflowStorage[WorkflowId] {
  override def getEvents(id: WorkflowId): ConnectionIO[List[IArray[Byte]]] = {
    sql"SELECT event_data FROM workflow_journal WHERE workflow_id = $id".query[Array[Byte]].map(IArray.unsafeFromArray).to[List]
  }

  override def saveEvent(id: WorkflowId, event: IArray[Byte]): ConnectionIO[Unit] = {
    val commitQuery = sql"COMMIT".update.run
    val insertQuery = sql"INSERT INTO workflow_journal (workflow_id, event_data) VALUES ($id, ${event.toArray})".update.run

    (insertQuery, commitQuery).mapN(_ + _).void
  }

  override def lockWorkflow(id: WorkflowId): Resource[ConnectionIO, Unit] = {
    Resource.eval(sql"BEGIN IMMEDIATE".update.run.void)
  }
}
