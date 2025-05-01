package workflows4s.doobie.postgres

import cats.effect.kernel.{Resource, Sync}
import cats.implicits.toFunctorOps
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.{ByteCodec, WorkflowStorage}

class PostgresWorkflowStorage[Event](tableName: String = "workflow_journal")(using evenCodec: ByteCodec[Event]) extends WorkflowStorage[WorkflowId, Event] {

  val tableNameFr = Fragment.const(tableName)

  override def getEvents(id: WorkflowId): fs2.Stream[ConnectionIO, Event] = {
    sql"select event_data from ${tableNameFr} where workflow_id = ${id}"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(evenCodec.read(IArray.unsafeFromArray(bytes))))
  }

  override def saveEvent(id: WorkflowId, event: Event): ConnectionIO[Unit] = {
    val bytes = IArray.genericWrapArray(evenCodec.write(event)).toArray
    sql"insert into ${tableNameFr} (workflow_id, event_data) values ($id, $bytes)".update.run.void
  }

  override def lockWorkflow(id: WorkflowId): Resource[ConnectionIO, Unit] = {
    // Acquires transaction-level exclusive lock
    val acquire = sql"select pg_try_advisory_xact_lock(${id})"
      .query[Boolean]
      .unique
      .flatMap(lockAcquired => Sync[ConnectionIO].raiseWhen(!lockAcquired)(new Exception(s"Couldn't acquire lock for ${id}")))
    Resource.eval(acquire)
  }
}
