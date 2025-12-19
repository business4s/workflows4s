package workflows4s.doobie.postgres

import cats.effect.IO
import cats.effect.kernel.{Resource, Sync}
import cats.implicits.toFunctorOps
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import workflows4s.doobie.{ByteCodec, WorkflowStorage}
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.utils.StringUtils

class PostgresWorkflowStorage[Event](xa: Transactor[IO], tableName: String = "workflow_journal")(using evenCodec: ByteCodec[Event])
    extends WorkflowStorage[IO, Event] {

  val tableNameFr = Fragment.const(tableName)

  override def getEvents(id: WorkflowInstanceId): fs2.Stream[IO, Event] = {
    sql"select event_data from ${tableNameFr} where instance_id = ${id.instanceId} and template_id = ${id.templateId} order by event_id"
      .query[Array[Byte]]
      .stream
      .evalMap(bytes => Sync[ConnectionIO].fromTry(evenCodec.read(IArray.unsafeFromArray(bytes))))
      .transact(xa)
  }

  override def saveEvent(id: WorkflowInstanceId, event: Event): IO[Unit] = {
    val bytes = IArray.genericWrapArray(evenCodec.write(event)).toArray
    sql"insert into ${tableNameFr} (instance_id, template_id, event_data) values (${id.instanceId}, ${id.templateId}, $bytes)".update.run.void
      .transact(xa)
  }

  override def lockWorkflow(id: WorkflowInstanceId): Resource[IO, Unit] = {
    // Acquires transaction-level exclusive lock
    val acquire = sql"select pg_try_advisory_xact_lock(${computeLockKey(id)})"
      .query[Boolean]
      .unique
      .flatMap(lockAcquired => Sync[ConnectionIO].raiseWhen(!lockAcquired)(new Exception(s"Couldn't acquire lock ${computeLockKey(id)} for ${id}")))
    Resource.eval(acquire.transact(xa))
  }

  /** Postgres locks are identified with a single bigint. We use a SHA-256 hash of the string to generate a unique bigint You may override this method
    * to use a different lock key computation
    */
  protected def computeLockKey(id: WorkflowInstanceId): Long = StringUtils.stringToLong(s"${id.templateId}-${id.instanceId}")
}
