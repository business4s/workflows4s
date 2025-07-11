package workflows4s.doobie.postgres

import cats.effect.{IO, Sync}
import cats.implicits.toFunctorOps
import doobie.{ConnectionIO, *}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus

import java.time.{Clock, Instant}
import java.sql.Timestamp
import scala.concurrent.duration.FiniteDuration

type WorkflowType = String
type WorkflowId   = String

trait PostgresWorkflowRegistry {

  def agent: WorkflowRegistry.Agent

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

    override val agent: WorkflowRegistry.Agent = (id: WorkflowInstanceId, executionStatus: ExecutionStatus) => {
      val query = for {
        now <- Sync[ConnectionIO].delay(Instant.now(clock))
        _   <- executionStatus match {
                 case ExecutionStatus.Running                             =>
                   sql"""INSERT INTO $tableNameFr (workflow_id, workflow_type, updated_at)
                      |VALUES (${id.runtimeId}, ${id.runtimeId}, ${Timestamp.from(now)})
                      |ON CONFLICT (workflow_id, workflow_type)
                      |DO UPDATE SET updated_at = ${Timestamp.from(now)}
                      |WHERE $tableNameFr.updated_at <= ${Timestamp.from(now)}""".stripMargin.update.run.void
                 case ExecutionStatus.Finished | ExecutionStatus.Awaiting =>
                   sql"""DELETE FROM $tableNameFr
                      |WHERE workflow_id = ${id}
                      |  and workflow_type = ${id.runtimeId}
                      |  and $tableNameFr.updated_at <= ${Timestamp.from(now)}""".stripMargin.update.run.void
               }
      } yield ()

      query.transact(xa)
    }

    override def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId] = {
      for {
        now       <- fs2.Stream.eval(Sync[ConnectionIO].delay(Instant.now(clock)))
        cutoffTime = now.minusMillis(notUpdatedFor.toMillis)
        elem      <- sql"""SELECT workflow_type, workflow_id
                     |FROM ${tableNameFr}
                     |WHERE updated_at <= ${Timestamp.from(cutoffTime)}""".stripMargin
                       .query[(WorkflowType, WorkflowId)]
                       .stream
      } yield WorkflowInstanceId(elem._1, elem._2)
    }
  }
}
