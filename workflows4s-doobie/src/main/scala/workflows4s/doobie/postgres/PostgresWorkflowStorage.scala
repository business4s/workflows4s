package workflows4s.doobie.postgres

import cats.effect.kernel.{Resource, Sync}
import cats.implicits.toFunctorOps
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.WorkflowStorage

object PostgresWorkflowStorage extends WorkflowStorage[WorkflowId] {
  override def getEvents(id: WorkflowId): ConnectionIO[List[IArray[Byte]]] = {
    sql"select event_data from workflow_journal where workflow_id = ${id}".query[Array[Byte]].map(IArray.unsafeFromArray).to[List]
  }

  override def saveEvent(id: WorkflowId, event: IArray[Byte]): ConnectionIO[Unit] = {
    val bytes = IArray.genericWrapArray(event).toArray
    sql"insert into workflow_journal (workflow_id, event_data) values ($id, $bytes)".update.run.void
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
