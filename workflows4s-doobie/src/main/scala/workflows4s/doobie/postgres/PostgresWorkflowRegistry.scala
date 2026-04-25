package workflows4s.doobie.postgres

import cats.data.NonEmptyList
import cats.effect.{IO, ResourceIO, Sync}
import cats.implicits.*
import com.typesafe.scalalogging.StrictLogging
import doobie.*
import doobie.implicits.*
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.parser.parse
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.registry.{WorkflowRegistry, WorkflowSearch}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.ActiveWorkflow

import java.sql.Timestamp
import java.time.{Clock, Instant}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Single postgres-backed table that powers the registry, knocker upper (wakeup poller), and workflow search. One row per workflow instance;
  * `wakeup_at` drives the poller. Use [[initialize]] as a `Resource` to start the poller.
  *
  * Finished rows are deleted by default; pass `keepFinished = true` to retain them. Concurrent upserts are throttled by `updated_at` so a stale write
  * never overwrites a fresher one.
  */
trait PostgresWorkflowRegistry
    extends WorkflowRegistry.Agent
    with KnockerUpper.Agent
    with KnockerUpper.Process[IO, ResourceIO[Unit]]
    with WorkflowSearch[IO] {

  /** Workflows last seen `Running` that have not been updated for at least `notUpdatedFor`. Use to recover instances whose execution was interrupted.
    */
  def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId]
}

object PostgresWorkflowRegistry {

  def apply(
      xa: Transactor[IO],
      tableName: String = "workflow_registry",
      pollInterval: FiniteDuration = 1.second,
      clock: Clock = Clock.systemUTC(),
      keepFinished: Boolean = false,
      taggers: Map[String, WorkflowRegistry.Tagger[?]] = Map.empty,
  ): IO[PostgresWorkflowRegistry] =
    IO(new Impl(xa, tableName, pollInterval, clock, keepFinished, taggers))

  private class Impl(
      xa: Transactor[IO],
      tableName: String,
      pollInterval: FiniteDuration,
      clock: Clock,
      keepFinished: Boolean,
      taggers: Map[String, WorkflowRegistry.Tagger[?]],
  ) extends PostgresWorkflowRegistry
      with StrictLogging {

    private val tableFr                 = Fragment.const(tableName)
    // The schema's CHECK constraint on `status` and the partial index `idx_workflow_registry_running_updated`
    // depend on the exact strings produced here — keep them in sync if ExecutionStatus values are renamed.
    private given Meta[ExecutionStatus] = Meta[String].imap(ExecutionStatus.valueOf)(_.toString)

    override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): IO[Unit] = {
      val id       = inst.id
      val nowTs    = Timestamp.from(Instant.now(clock))
      val tagsJson = computeTagsJson(inst)

      val query = executionStatus match {
        case ExecutionStatus.Running | ExecutionStatus.Awaiting =>
          sql"""INSERT INTO $tableFr (template_id, instance_id, status, created_at, updated_at, tags)
               |VALUES (${id.templateId}, ${id.instanceId}, $executionStatus, $nowTs, $nowTs, $tagsJson::jsonb)
               |ON CONFLICT (template_id, instance_id)
               |DO UPDATE SET status = $executionStatus, updated_at = $nowTs, tags = $tagsJson::jsonb
               |WHERE $tableFr.updated_at <= $nowTs""".stripMargin.update.run.void
        case ExecutionStatus.Finished if keepFinished           =>
          sql"""INSERT INTO $tableFr (template_id, instance_id, status, created_at, updated_at, wakeup_at, tags)
               |VALUES (${id.templateId}, ${id.instanceId}, ${ExecutionStatus.Finished}, $nowTs, $nowTs, NULL, $tagsJson::jsonb)
               |ON CONFLICT (template_id, instance_id)
               |DO UPDATE SET status = ${ExecutionStatus.Finished}, updated_at = $nowTs, wakeup_at = NULL, tags = $tagsJson::jsonb
               |WHERE $tableFr.updated_at <= $nowTs""".stripMargin.update.run.void
        case ExecutionStatus.Finished                           =>
          sql"""DELETE FROM $tableFr
               |WHERE template_id = ${id.templateId}
               |  AND instance_id = ${id.instanceId}
               |  AND $tableFr.updated_at <= $nowTs""".stripMargin.update.run.void
      }
      query.transact(xa)
    }

    override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = {
      val nowTs = Timestamp.from(Instant.now(clock))
      val query = at match {
        case Some(t) =>
          // Upsert so a wakeup can be scheduled before the engine has registered the instance.
          sql"""INSERT INTO $tableFr (template_id, instance_id, status, created_at, updated_at, wakeup_at)
               |VALUES (${id.templateId}, ${id.instanceId}, ${ExecutionStatus.Awaiting}, $nowTs, $nowTs, ${Timestamp.from(t)})
               |ON CONFLICT (template_id, instance_id)
               |DO UPDATE SET wakeup_at = ${Timestamp.from(t)}""".stripMargin.update.run.void
        case None    =>
          // Plain update — don't resurrect a row deleted because the workflow finished.
          sql"""UPDATE $tableFr SET wakeup_at = NULL
               |WHERE template_id = ${id.templateId} AND instance_id = ${id.instanceId}""".stripMargin.update.run.void
      }
      query.transact(xa)
    }

    override def initialize(wakeUp: WorkflowInstanceId => IO[Unit]): ResourceIO[Unit] =
      pollerStream(wakeUp).compile.drain.background.void

    private def pollerStream(wakeUp: WorkflowInstanceId => IO[Unit]): fs2.Stream[IO, Unit] =
      fs2.Stream
        .awakeEvery[IO](pollInterval)
        .evalMap(_ => dispatchDue(wakeUp).handleErrorWith(e => IO(logger.error("Poller tick failed", e))))

    // Single UPDATE...RETURNING atomically claims all due rows; concurrent pollers can't double-fire because the WHERE filters out NULL wakeups.
    // Trade-off: a wakeup whose claim commits but whose callback is cancelled (shutdown, fiber interrupt) or fails is lost — the row's wakeup_at
    // is already NULL. Such workflows are expected to be re-woken by other mechanisms (engine startup scan, incoming signals).
    private def dispatchDue(wakeUp: WorkflowInstanceId => IO[Unit]): IO[Unit] = {
      val nowTs = Timestamp.from(Instant.now(clock))
      sql"""UPDATE $tableFr SET wakeup_at = NULL
           |WHERE wakeup_at IS NOT NULL AND wakeup_at <= $nowTs""".stripMargin.update
        .withGeneratedKeys[(String, String)]("template_id", "instance_id")
        .compile
        .toList
        .transact(xa)
        .flatMap(_.parTraverse_ { case (tpl, inst) =>
          val id = WorkflowInstanceId(tpl, inst)
          IO(logger.debug(s"Dispatching wakeup for $id")) *> wakeUp(id)
        })
    }

    override def getExecutingWorkflows(notUpdatedFor: FiniteDuration): fs2.Stream[ConnectionIO, WorkflowInstanceId] =
      for {
        now       <- fs2.Stream.eval(Sync[ConnectionIO].delay(Instant.now(clock)))
        cutoffTime = now.minusMillis(notUpdatedFor.toMillis)
        elem      <- sql"""SELECT template_id, instance_id
                          |FROM $tableFr
                          |WHERE status = ${ExecutionStatus.Running} AND updated_at <= ${Timestamp.from(cutoffTime)}
                          |ORDER BY updated_at ASC, template_id ASC, instance_id ASC""".stripMargin
                       .query[(String, String)]
                       .stream
      } yield WorkflowInstanceId(elem._1, elem._2)

    override def search(templateId: String, query: WorkflowSearch.Query): IO[List[WorkflowSearch.Result]] = {
      val whereFr  = buildWhere(templateId, query)
      val orderFr  = query.sort match {
        case Some(WorkflowSearch.SortBy.CreatedAsc)  => fr"ORDER BY created_at ASC"
        case Some(WorkflowSearch.SortBy.CreatedDesc) => fr"ORDER BY created_at DESC"
        case Some(WorkflowSearch.SortBy.UpdatedAsc)  => fr"ORDER BY updated_at ASC"
        case Some(WorkflowSearch.SortBy.UpdatedDesc) => fr"ORDER BY updated_at DESC"
        case Some(WorkflowSearch.SortBy.WakeupAsc)   => fr"ORDER BY wakeup_at ASC NULLS LAST"
        case Some(WorkflowSearch.SortBy.WakeupDesc)  => fr"ORDER BY wakeup_at DESC NULLS LAST"
        case None                                    => Fragment.empty
      }
      val limitFr  = query.limit.map(l => fr"LIMIT $l").getOrElse(Fragment.empty)
      val offsetFr = query.offset.map(o => fr"OFFSET $o").getOrElse(Fragment.empty)

      (fr"SELECT template_id, instance_id, status, created_at, updated_at, wakeup_at, tags::text FROM" ++
        tableFr ++ fr" " ++ whereFr ++ fr" " ++ orderFr ++ fr" " ++ limitFr ++ fr" " ++ offsetFr)
        .query[Row]
        .to[List]
        .map(_.map(_.toResult))
        .transact(xa)
    }

    override def count(templateId: String, query: WorkflowSearch.Query): IO[Int] = {
      val whereFr = buildWhere(templateId, query)
      (fr"SELECT COUNT(*) FROM" ++ tableFr ++ fr" " ++ whereFr)
        .query[Int]
        .unique
        .transact(xa)
    }

    private def buildWhere(templateId: String, query: WorkflowSearch.Query): Fragment = {
      val frags = List.newBuilder[Fragment]
      frags += fr"template_id = $templateId"
      NonEmptyList.fromList(query.status.toList).foreach { nel =>
        frags += Fragments.in(fr"status", nel)
      }
      query.createdAfter.foreach(t => frags += fr"created_at > ${Timestamp.from(t)}")
      query.createdBefore.foreach(t => frags += fr"created_at < ${Timestamp.from(t)}")
      query.updatedAfter.foreach(t => frags += fr"updated_at > ${Timestamp.from(t)}")
      query.updatedBefore.foreach(t => frags += fr"updated_at < ${Timestamp.from(t)}")
      query.wakeupAfter.foreach(t => frags += fr"wakeup_at > ${Timestamp.from(t)}")
      query.wakeupBefore.foreach(t => frags += fr"wakeup_at < ${Timestamp.from(t)}")
      query.tagFilters.foreach {
        case WorkflowSearch.TagFilter.HasKey(k)       => frags += fr"jsonb_exists(tags, $k)"
        case WorkflowSearch.TagFilter.Equals(k, v)    =>
          frags += fr"tags @> ${singleEntryJson(k, v)}::jsonb"
        case WorkflowSearch.TagFilter.In(k, vs)       =>
          NonEmptyList.fromList(vs.toList.map(v => fr"tags @> ${singleEntryJson(k, v)}::jsonb")) match {
            case Some(nel) => frags += fr"(" ++ nel.toList.intercalate(fr" OR ") ++ fr")"
            case None      => frags += fr"FALSE"
          }
        case WorkflowSearch.TagFilter.NotEquals(k, v) =>
          frags += fr"jsonb_exists(tags, $k) AND NOT (tags @> ${singleEntryJson(k, v)}::jsonb)"
      }
      fr"WHERE" ++ frags.result().intercalate(fr" AND ")
    }

    // Default `'{}'` skips Json serialization on the hot path when the template has no tagger configured.
    private def computeTagsJson(inst: ActiveWorkflow[?]): String =
      taggers.get(inst.id.templateId) match {
        case Some(tagger) =>
          val tags = tagger.getTags(inst.id, inst.liveState.asInstanceOf)
          if tags.isEmpty then "{}"
          else Json.fromFields(tags.view.mapValues(Json.fromString).toMap).noSpaces
        case None         => "{}"
      }

    private def singleEntryJson(key: String, value: String): String =
      Json.obj(key -> Json.fromString(value)).noSpaces
  }

  private case class Row(
      templateId: String,
      instanceId: String,
      status: ExecutionStatus,
      createdAt: Timestamp,
      updatedAt: Timestamp,
      wakeupAt: Option[Timestamp],
      tagsJson: String,
  ) {
    def toResult: WorkflowSearch.Result = {
      val tagsMap = parse(tagsJson).toOption
        .flatMap(_.asObject)
        .map(_.toMap.collect { case (k, v) if v.isString => k -> v.asString.get })
        .getOrElse(Map.empty)
      WorkflowSearch.Result(
        WorkflowInstanceId(templateId, instanceId),
        status,
        createdAt.toInstant,
        updatedAt.toInstant,
        tagsMap,
        wakeupAt.map(_.toInstant),
      )
    }
  }
}
