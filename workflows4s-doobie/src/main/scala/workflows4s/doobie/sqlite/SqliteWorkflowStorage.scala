package workflows4s.doobie.sqlite

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.free.connection.raw
import workflows4s.doobie.{ByteCodec, WorkflowStorage}

class SqliteWorkflowStorage[Event]()(using eventCodec: ByteCodec[Event]) extends WorkflowStorage[Unit, Event] {
  override def getEvents(id: Unit): fs2.Stream[ConnectionIO, Event] =
    sql"SELECT event_data FROM workflow_journal ORDER BY event_id"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(eventCodec.read(IArray.unsafeFromArray(bytes))))

  override def saveEvent(id: Unit, event: Event): ConnectionIO[Unit] = {
    val bytes = IArray.genericWrapArray(eventCodec.write(event)).toArray
    sql"INSERT INTO workflow_journal (event_data) VALUES ($bytes)".update.run.void
  }

  override def lockWorkflow(id: Unit): Resource[ConnectionIO, Unit] = {
    // SQLite locks the entire database with BEGIN IMMEDIATE
    // This acquires a write lock on the database preventing other connections from writing
    val acquire = raw(conn => conn.prepareStatement("BEGIN IMMEDIATE").execute()).void
    val release = raw(conn => conn.prepareStatement("COMMIT").execute()).void
    Resource.make(acquire)(_ => release)
  }
}
