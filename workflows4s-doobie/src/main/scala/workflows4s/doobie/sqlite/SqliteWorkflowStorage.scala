package workflows4s.doobie.sqlite

import cats.effect.kernel.{Resource, Sync}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.{ByteCodec, WorkflowStorage}

class SqliteWorkflowStorage[Event](tableName: String = "workflow_journal")(using eventCodec: ByteCodec[Event])
    extends WorkflowStorage[String, Event] {
  override def getEvents(id: String): fs2.Stream[ConnectionIO, Event] =
    sql"SELECT event_data FROM workflow_journal"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(eventCodec.read(IArray.unsafeFromArray(bytes))))

  override def saveEvent(id: String, event: Event): ConnectionIO[Unit] = {
    val bytes = IArray.genericWrapArray(eventCodec.write(event)).toArray
    sql"INSERT INTO workflow_journal (event_data) VALUES ($bytes)".update.run.void
  }

  override def lockWorkflow(id: String): Resource[ConnectionIO, Unit] = {
    Resource.pure[ConnectionIO, Unit](())
  }
}
