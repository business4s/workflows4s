package workflows4s.runtime.registry

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.wio.{ActiveWorkflow, WeakSync}

import java.time.{Clock, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class InMemoryWorkflowRegistry[F[_]: WeakSync](
    clock: Clock = Clock.systemUTC(),
    taggers: Map[String, WorkflowRegistry.Tagger[?]] = Map.empty,
) extends WorkflowRegistry.Agent[F]
    with WorkflowSearch[F]
    with StrictLogging {

  import InMemoryWorkflowRegistry.*

  private val state = new ConcurrentHashMap[WorkflowInstanceId, Data]()

  override def upsertInstance(inst: ActiveWorkflow[?], executionStatus: ExecutionStatus): F[Unit] = WeakSync[F].delay {
    val id  = inst.id
    val now = Instant.now(clock)
    logger.info(s"Updating workflow registry for ${id} to status $executionStatus at $now")
    state.compute(
      id,
      (_, existing) =>
        Option(existing) match {
          case Some(ex) if ex.updatedAt.isAfter(now) => ex
          case Some(ex)                              => ex.copy(updatedAt = now, status = executionStatus)
          case None                                  => Data(id, now, now, executionStatus, inst.wakeupAt, getTags(inst))
        },
    ): Unit
  }

  private def getTags(instance: ActiveWorkflow[?]): Map[String, String] =
    taggers
      .get(instance.id.templateId)
      .map(_.getTags(instance.id, instance.liveState.asInstanceOf))
      .getOrElse(Map())

  def getWorkflows(): F[List[Data]] = WeakSync[F].delay {
    state.values().asScala.toList
  }

  override def search(templateId: String, query: WorkflowSearch.Query): F[List[WorkflowSearch.Result]] = WeakSync[F].delay {
    val filters  = buildFilters(templateId, query)
    val filtered = state.values().asScala.toList.filter(x => filters.forall(_.apply(x)))
    val sorted   = query.sort match {
      case Some(WorkflowSearch.SortBy.CreatedAsc)  => filtered.sortBy(_.createdAt)
      case Some(WorkflowSearch.SortBy.CreatedDesc) => filtered.sortBy(_.createdAt)(using Ordering[Instant].reverse)
      case Some(WorkflowSearch.SortBy.UpdatedAsc)  => filtered.sortBy(_.updatedAt)
      case Some(WorkflowSearch.SortBy.UpdatedDesc) => filtered.sortBy(_.updatedAt)(using Ordering[Instant].reverse)
      case Some(WorkflowSearch.SortBy.WakeupAsc)   => filtered.sortBy(_.wakeupAt)
      case Some(WorkflowSearch.SortBy.WakeupDesc)  => filtered.sortBy(_.wakeupAt)(using Ordering.Option(using Ordering[Instant]).reverse)
      case None                                    => filtered
    }
    val paged    = sorted
      .drop(query.offset.getOrElse(0))
      .take(query.limit.getOrElse(sorted.size))

    paged.map(d => WorkflowSearch.Result(d.id, d.status, d.createdAt, d.updatedAt, d.tags, d.wakeupAt))
  }

  override def count(templateId: String, query: WorkflowSearch.Query): F[Int] = WeakSync[F].delay {
    val filters = buildFilters(templateId, query)
    state.values().asScala.count(x => filters.forall(_.apply(x)))
  }

  private def buildFilters(templateId: String, query: WorkflowSearch.Query): List[Data => Boolean] =
    List(
      _.id.templateId == templateId,
      (d: Data) => query.status.isEmpty || query.status.contains(d.status),
      (d: Data) => query.createdAfter.forall(d.createdAt.isAfter),
      (d: Data) => query.createdBefore.forall(d.createdAt.isBefore),
      (d: Data) => query.updatedAfter.forall(d.updatedAt.isAfter),
      (d: Data) => query.updatedBefore.forall(d.updatedAt.isBefore),
      (d: Data) => query.wakeupAfter.forall(after => d.wakeupAt.exists(_.isAfter(after))),
      (d: Data) => query.wakeupBefore.forall(before => d.wakeupAt.exists(_.isBefore(before))),
      (d: Data) =>
        query.tagFilters.forall {
          case WorkflowSearch.TagFilter.HasKey(k)       => d.tags.contains(k)
          case WorkflowSearch.TagFilter.Equals(k, v)    => d.tags.get(k).contains(v)
          case WorkflowSearch.TagFilter.In(k, vs)       => d.tags.get(k).exists(vs.contains)
          case WorkflowSearch.TagFilter.NotEquals(k, v) => d.tags.get(k).exists(_ != v)
        },
    )
}

object InMemoryWorkflowRegistry {

  case class Data(
      id: WorkflowInstanceId,
      createdAt: Instant,
      updatedAt: Instant,
      status: ExecutionStatus,
      wakeupAt: Option[Instant],
      tags: Map[String, String],
  )

}
