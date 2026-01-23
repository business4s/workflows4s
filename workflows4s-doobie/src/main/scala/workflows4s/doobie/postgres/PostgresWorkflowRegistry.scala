package workflows4s.doobie.postgres

import java.sql.Timestamp
import java.time.{Clock, Instant}

import scala.concurrent.duration.FiniteDuration

import cats.data.NonEmptyList
import cats.effect.{IO, Sync}
import cats.implicits.toFunctorOps
import doobie.implicits.*
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, *}
import io.circe.syntax.*
import io.circe.parser.*
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.registry.{WorkflowRegistry, WorkflowSearch}
import workflows4s.wio.ActiveWorkflow

trait PostgresWorkflowRegistry extends WorkflowRegistry.Agent with WorkflowSearch[IO] {

  /** Returns all workflows that were last seen as running and were not updated for at least `notUpdatedFor`. Useful for detecting stuck workflows
    * that may need recovery.
    */
  def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId]
}

object PostgresWorkflowRegistry {

  def apply(
      xa: Transactor[IO],
      tableName: String = "executing_workflows",
      clock: Clock = Clock.systemUTC(),
      taggers: Map[String, WorkflowRegistry.Tagger[?]] = Map.empty,
  ): IO[PostgresWorkflowRegistry] = {
    IO(new Impl(tableName, xa, clock, taggers))
  }

  private class Impl(
      tableName: String,
      xa: Transactor[IO],
      clock: Clock,
      taggers: Map[String, WorkflowRegistry.Tagger[?]],
  ) extends PostgresWorkflowRegistry {

    private val tableNameFr = Fragment.const(tableName)

    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit] = {
      val id       = inst.id
      val status   = statusToString(executionStatus)
      val tags     = getTags(inst)
      val tagsJson = tagsToJson(tags)
      val query    = for {
        now <- Sync[ConnectionIO].delay(Instant.now(clock))
        _   <- sql"""INSERT INTO $tableNameFr (instance_id, template_id, status, created_at, updated_at, wakeup_at, tags)
                    |VALUES (${id.instanceId}, ${id.templateId}, $status, ${Timestamp.from(now)}, ${Timestamp.from(now)}, ${inst.wakeupAt.map(
                     Timestamp.from,
                   )}, $tagsJson::jsonb)
                  |ON CONFLICT (template_id, instance_id)
                  |DO UPDATE SET status = $status, updated_at = ${Timestamp.from(now)}, wakeup_at = ${inst.wakeupAt.map(
                     Timestamp.from,
                   )}, tags = $tagsJson::jsonb
                  |WHERE $tableNameFr.updated_at <= ${Timestamp.from(now)}""".stripMargin.update.run.void
      } yield ()
      query.transact(xa)
    }

    private def getTags(instance: ActiveWorkflow[?]): Map[String, String] =
      taggers
        .get(instance.id.templateId)
        .map(_.getTags(instance.id, instance.liveState.asInstanceOf))
        .getOrElse(Map.empty)

    private def tagsToJson(tags: Map[String, String]): String =
      tags.asJson.noSpaces

    private def jsonToTags(json: String): Map[String, String] =
      if json == null || json == "{}" then Map.empty
      else decode[Map[String, String]](json).getOrElse(Map.empty)

    private def statusToString(status: ExecutionStatus): String = status match {
      case ExecutionStatus.Running  => "running"
      case ExecutionStatus.Awaiting => "awaiting"
      case ExecutionStatus.Finished => "finished"
    }

    private def stringToStatus(s: String): ExecutionStatus = s match {
      case "running"  => ExecutionStatus.Running
      case "awaiting" => ExecutionStatus.Awaiting
      case "finished" => ExecutionStatus.Finished
      case other      => throw new IllegalArgumentException(s"Unknown workflow status: $other")
    }

    override def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId] =
      for {
        now       <- fs2.Stream.eval(Sync[ConnectionIO].delay(Instant.now(clock)))
        cutoffTime = now.minusMillis(notUpdatedFor.toMillis)
        elem      <- sql"""SELECT template_id, instance_id FROM $tableNameFr
                          |WHERE status = 'running' AND updated_at <= ${Timestamp.from(cutoffTime)}""".stripMargin
                       .query[(String, String)]
                       .stream
      } yield WorkflowInstanceId(elem._1, elem._2)

    override def search(templateId: String, query: WorkflowSearch.Query): IO[List[WorkflowSearch.Result]] = {
      val filters = buildFilters(templateId, query)
      val orderBy = buildOrderBy(query.sort)
      val limit   = query.limit.map(l => fr"LIMIT $l").getOrElse(fr"")
      val offset  = query.offset.map(o => fr"OFFSET $o").getOrElse(fr"")

      val fullQuery =
        fr"SELECT instance_id, template_id, status, created_at, updated_at, wakeup_at, tags::text FROM" ++ tableNameFr ++ fr"WHERE" ++ filters ++ orderBy ++ limit ++ offset

      fullQuery
        .query[(String, String, String, Timestamp, Timestamp, Option[Timestamp], String)]
        .to[List]
        .map(_.map { case (instanceId, tplId, status, createdAt, updatedAt, wakeupAt, tagsJson) =>
          WorkflowSearch.Result(
            WorkflowInstanceId(tplId, instanceId),
            stringToStatus(status),
            createdAt.toInstant,
            updatedAt.toInstant,
            jsonToTags(tagsJson),
            wakeupAt.map(_.toInstant),
          )
        })
        .transact(xa)
    }

    override def count(templateId: String, query: WorkflowSearch.Query): IO[Int] = {
      val filters   = buildFilters(templateId, query)
      val fullQuery = fr"SELECT COUNT(*) FROM" ++ tableNameFr ++ fr"WHERE" ++ filters
      fullQuery.query[Int].unique.transact(xa)
    }

    private def buildFilters(templateId: String, query: WorkflowSearch.Query): Fragment = {
      val filters = List(
        Some(fr"template_id = $templateId"),
        NonEmptyList.fromList(query.status.map(statusToString).toList).map(nel => Fragments.in(fr"status", nel)),
        query.createdAfter.map(t => fr"created_at > ${Timestamp.from(t)}"),
        query.createdBefore.map(t => fr"created_at < ${Timestamp.from(t)}"),
        query.updatedAfter.map(t => fr"updated_at > ${Timestamp.from(t)}"),
        query.updatedBefore.map(t => fr"updated_at < ${Timestamp.from(t)}"),
        query.wakeupAfter.map(t => fr"wakeup_at > ${Timestamp.from(t)}"),
        query.wakeupBefore.map(t => fr"wakeup_at < ${Timestamp.from(t)}"),
      ).flatten ++ query.tagFilters.map {
        case WorkflowSearch.TagFilter.HasKey(k)       => fr"tags ? $k"
        case WorkflowSearch.TagFilter.Equals(k, v)    => fr"tags ->> $k = $v"
        case WorkflowSearch.TagFilter.In(k, vs)       =>
          NonEmptyList.fromList(vs.toList).map(nel => Fragments.in(fr"tags ->> $k", nel)).getOrElse(fr"FALSE")
        case WorkflowSearch.TagFilter.NotEquals(k, v) => fr"tags ->> $k <> $v"
      }
      filters.reduceOption(_ ++ fr" AND " ++ _).getOrElse(fr"TRUE")
    }

    private def buildOrderBy(sort: Option[WorkflowSearch.SortBy]): Fragment = sort match {
      case Some(WorkflowSearch.SortBy.CreatedAsc)  => fr"ORDER BY created_at ASC"
      case Some(WorkflowSearch.SortBy.CreatedDesc) => fr"ORDER BY created_at DESC"
      case Some(WorkflowSearch.SortBy.UpdatedAsc)  => fr"ORDER BY updated_at ASC"
      case Some(WorkflowSearch.SortBy.UpdatedDesc) => fr"ORDER BY updated_at DESC"
      case Some(WorkflowSearch.SortBy.WakeupAsc)   => fr"ORDER BY wakeup_at ASC NULLS LAST"
      case Some(WorkflowSearch.SortBy.WakeupDesc)  => fr"ORDER BY wakeup_at DESC NULLS LAST"
      case None                                    => fr""
    }
  }
}
