package workflows4s.doobie.postgres

import cats.effect.{IO, Sync}
import cats.implicits.toFunctorOps
import doobie.{ConnectionIO, *}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.ActiveWorkflow

import java.time.{Clock, Instant}
import java.sql.Timestamp
import scala.concurrent.duration.FiniteDuration

trait PostgresWorkflowRegistry extends WorkflowRegistry.Agent[IO] {

  // returns all the workflows that were last seen as running and were not updated for at least `notUpdatedFor`
  def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId]
}

object PostgresWorkflowRegistry {
  def apply(
      xa: Transactor[IO],
      tableName: String = "executing_workflows",
      clock: Clock = Clock.systemUTC(),
  ): IO[PostgresWorkflowRegistry] = {
    IO(new Impl(tableName, xa, clock))
  }

  class Impl(tableName: String, xa: Transactor[IO], clock: Clock) extends PostgresWorkflowRegistry {

    val tableNameFr = Fragment.const(tableName)

    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit] = {
      val id    = inst.id
      val query = for {
        now <- Sync[ConnectionIO].delay(Instant.now(clock))
        _   <- executionStatus match {
                 case ExecutionStatus.Running                             =>
                   sql"""INSERT INTO $tableNameFr (instance_id, template_id, updated_at)
                      |VALUES (${id.instanceId}, ${id.templateId}, ${Timestamp.from(now)})
                      |ON CONFLICT (instance_id, template_id)
                      |DO UPDATE SET updated_at = ${Timestamp.from(now)}
                      |WHERE $tableNameFr.updated_at <= ${Timestamp.from(now)}""".stripMargin.update.run.void
                 case ExecutionStatus.Finished | ExecutionStatus.Awaiting =>
                   sql"""DELETE FROM $tableNameFr
                      |WHERE instance_id = ${id.instanceId}
                      |  and template_id = ${id.templateId}
                      |  and $tableNameFr.updated_at <= ${Timestamp.from(now)}""".stripMargin.update.run.void
               }
      } yield ()

      query.transact(xa)
    }

    override def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId] = {
      for {
        now       <- fs2.Stream.eval(Sync[ConnectionIO].delay(Instant.now(clock)))
        cutoffTime = now.minusMillis(notUpdatedFor.toMillis)
        elem      <- sql"""SELECT template_id, instance_id
                          |FROM ${tableNameFr}
                          |WHERE updated_at <= ${Timestamp.from(cutoffTime)}""".stripMargin
                       .query[(String, String)]
                       .stream
      } yield WorkflowInstanceId(elem._1, elem._2)
    }
  }
}
