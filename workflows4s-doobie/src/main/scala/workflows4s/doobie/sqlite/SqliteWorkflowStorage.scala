package workflows4s.doobie.sqlite

import cats.effect.kernel.{Resource, Sync}
import cats.implicits.toFunctorOps
import doobie.*
import doobie.implicits.*
import workflows4s.doobie.WorkflowStorage

object SqliteWorkflowStorage extends WorkflowStorage[WorkflowId] {
  override def getEvents(id: WorkflowId): ConnectionIO[List[IArray[Byte]]] = {
    sql"SELECT event_data FROM workflow_journal WHERE workflow_id = $id".query[Array[Byte]].map(IArray.unsafeFromArray).to[List]
  }

  override def saveEvent(id: WorkflowId, event: IArray[Byte]): ConnectionIO[Unit] = {
    sql"INSERT INTO workflow_journal (workflow_id, event_data) VALUES ($id, ${event.toArray})".update.run.void
  }

  override def lockWorkflow(id: WorkflowId): Resource[ConnectionIO, Unit] = {
    // NOTE: workaround for sqlite because it doesn't support transanctional locks
    val acquire = for {
      _            <- sql"INSERT INTO workflow_locks (workflow_id) VALUES($id) ON CONFLICT DO NOTHING".update.run
      lockAcquired <- sql"SELECT 1 FROM workflow_locks WHERE workflow_id = $id".query[Int].option
      _            <- Sync[ConnectionIO].raiseWhen(lockAcquired.isEmpty)(new Exception("Coundn't acquire lock"))
    } yield ()
    Resource.eval(acquire)
  }
}
