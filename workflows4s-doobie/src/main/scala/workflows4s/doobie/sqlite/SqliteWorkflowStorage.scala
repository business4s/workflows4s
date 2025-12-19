package workflows4s.doobie.sqlite

import cats.effect.IO
import cats.effect.kernel.{Resource, Sync}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import workflows4s.doobie.{ByteCodec, WorkflowStorage}
import workflows4s.runtime.WorkflowInstanceId

/** SQLite workflow storage.
  *
  * Uses a cats-effect Semaphore for locking since SQLite has restrictive concurrency (single writer). Each SQLite database file gets its own storage
  * instance with its own lock.
  */
class SqliteWorkflowStorage[Event](xa: Transactor[IO], eventCodec: ByteCodec[Event], lock: Semaphore[IO]) extends WorkflowStorage[IO, Event] {
  override def getEvents(id: WorkflowInstanceId): fs2.Stream[IO, Event] =
    sql"SELECT event_data FROM workflow_journal ORDER BY event_id"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(eventCodec.read(IArray.unsafeFromArray(bytes))))
      .transact(xa)

  override def saveEvent(id: WorkflowInstanceId, event: Event): IO[Unit] = {
    val bytes = IArray.genericWrapArray(eventCodec.write(event)).toArray
    sql"INSERT INTO workflow_journal (event_data) VALUES ($bytes)".update.run.void
      .transact(xa)
  }

  override def lockWorkflow(id: WorkflowInstanceId): Resource[IO, Unit] = lock.permit
}

object SqliteWorkflowStorage {
  def create[Event](xa: Transactor[IO], eventCodec: ByteCodec[Event]): IO[SqliteWorkflowStorage[Event]] =
    Semaphore[IO](1).map(lock => new SqliteWorkflowStorage[Event](xa, eventCodec, lock))
}
