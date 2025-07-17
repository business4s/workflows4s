package workflows4s.doobie.sqlite

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.{ByteCodec, WorkflowStorage}
import workflows4s.runtime.WorkflowInstanceId

class SqliteWorkflowStorage[Event](eventCodec: ByteCodec[Event]) extends WorkflowStorage[Event] {
  override def getEvents(id: WorkflowInstanceId): fs2.Stream[ConnectionIO, Event] =
    sql"SELECT event_data FROM workflow_journal ORDER BY event_id"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(eventCodec.read(IArray.unsafeFromArray(bytes))))

  override def saveEvent(id: WorkflowInstanceId, event: Event): ConnectionIO[Unit] = {
    val bytes = IArray.genericWrapArray(eventCodec.write(event)).toArray
    sql"INSERT INTO workflow_journal (event_data) VALUES ($bytes)".update.run.void
  }

  override def lockWorkflow(id: WorkflowInstanceId): Resource[ConnectionIO, Unit] = {
    // SQLite locks the entire database with BEGIN IMMEDIATE
    // This acquires a write lock on the database preventing other connections from writing
    val acquire = sql"BEGIN IMMEDIATE".update.run.void
    val release = sql"COMMIT".update.run.void
    Resource.make(acquire)(_ => release)
  }
}
