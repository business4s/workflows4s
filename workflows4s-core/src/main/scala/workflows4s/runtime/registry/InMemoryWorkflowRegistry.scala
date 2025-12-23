package workflows4s.runtime.registry

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.instanceengine.{Effect, Ref}
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.ActiveWorkflow

import java.time.{Clock, Instant}

trait InMemoryWorkflowRegistry[F[_]] extends WorkflowRegistry.Agent[F] with WorkflowSearch[F] {

  def getWorkflows(): F[List[InMemoryWorkflowRegistry.Data]]

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

  def apply[F[_]](clock: Clock = Clock.systemUTC())(using E: Effect[F]): F[InMemoryWorkflowRegistry[F]] = {
    E.ref(Map.empty[WorkflowInstanceId, Data]).map { stateRef =>
      new Impl(stateRef, clock, Map())
    }
  }

  private class Impl[F[_]](
      stateRef: Ref[F, Map[WorkflowInstanceId, Data]],
      clock: Clock,
      taggers: Map[String, WorkflowRegistry.Tagger[?]],
  )(using E: Effect[F])
      extends InMemoryWorkflowRegistry[F]
      with StrictLogging {

    override def upsertInstance(inst: ActiveWorkflow[F, ?], executionStatus: ExecutionStatus): F[Unit] = {
      val id = inst.id
      for {
        now <- E.delay(Instant.now(clock))
        _    = logger.info(s"Updating workflow registry for ${id} to status $executionStatus at $now")
        _   <- stateRef.update { state =>
                 state.get(id) match {
                   case Some(existing) =>
                     if existing.updatedAt.isAfter(now) then state
                     else state + (id -> existing.copy(updatedAt = now, status = executionStatus))
                   case None           =>
                     state + (id -> Data(id, now, now, executionStatus, inst.wakeupAt, getTags(inst)))
                 }
               }
      } yield ()
    }

    private def getTags(instance: ActiveWorkflow[?, ?]) = {
      taggers
        .get(instance.id.templateId)
        .map(_.getTags(instance.id, instance.liveState.asInstanceOf))
        .getOrElse(Map())
    }

    override def getWorkflows(): F[List[Data]] = stateRef.get.map(_.values.toList)

    override def search(templateId: String, query: WorkflowSearch.Query): F[List[WorkflowSearch.Result]] = {
      val filters = buildFilters(templateId, query)
      for {
        state <- stateRef.get
      } yield {
        val filtered = state.values.toList.filter(x => filters.forall(_.apply(x)))
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
    }

    private def buildFilters(templateId: String, query: WorkflowSearch.Query): List[InMemoryWorkflowRegistry.Data => Boolean] = {
      List(
        // templateId filter
        _.id.templateId == templateId,
        // status filter
        (d: InMemoryWorkflowRegistry.Data) => query.status.isEmpty || query.status.contains(d.status),
        // createdAt filters
        (d: InMemoryWorkflowRegistry.Data) => query.createdAfter.forall(d.createdAt.isAfter),
        (d: InMemoryWorkflowRegistry.Data) => query.createdBefore.forall(d.createdAt.isBefore),
        // updatedAt filters
        (d: InMemoryWorkflowRegistry.Data) => query.updatedAfter.forall(d.updatedAt.isAfter),
        (d: InMemoryWorkflowRegistry.Data) => query.updatedBefore.forall(d.updatedAt.isBefore),
        // wakeupAt filters
        (d: InMemoryWorkflowRegistry.Data) => query.wakeupAfter.forall(after => d.wakeupAt.exists(_.isAfter(after))),
        (d: InMemoryWorkflowRegistry.Data) => query.wakeupBefore.forall(before => d.wakeupAt.exists(_.isBefore(before))),
        // tag filters
        (d: InMemoryWorkflowRegistry.Data) =>
          query.tagFilters.forall {
            case WorkflowSearch.TagFilter.HasKey(k)       => d.tags.contains(k)
            case WorkflowSearch.TagFilter.Equals(k, v)    => d.tags.get(k).contains(v)
            case WorkflowSearch.TagFilter.In(k, vs)       => d.tags.get(k).exists(vs.contains)
            case WorkflowSearch.TagFilter.NotEquals(k, v) => d.tags.get(k).exists(_ != v)
          },
      )

    }
  }

}
