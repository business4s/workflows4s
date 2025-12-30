package workflows4s.runtime.registry

import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.registry.WorkflowSearch.*

import java.time.Instant

trait WorkflowSearch[F[_]] {
  def search(templateId: String, query: Query): F[List[Result]]
  def count(templateId: String, query: Query): F[Int]
}

object WorkflowSearch {
  case class Query(
      status: Set[ExecutionStatus] = Set.empty,
      createdAfter: Option[Instant] = None,
      createdBefore: Option[Instant] = None,
      updatedAfter: Option[Instant] = None,
      updatedBefore: Option[Instant] = None,
      wakeupBefore: Option[Instant] = None,
      wakeupAfter: Option[Instant] = None,
      tagFilters: List[TagFilter] = Nil,
      sort: Option[SortBy] = None,
      limit: Option[Int] = None,
      offset: Option[Int] = None,
  ) {
    def forTotalCount: Query = this.copy(limit = None, offset = None, sort = None)
  }

  sealed trait TagFilter

  object TagFilter {
    case class HasKey(key: String)                   extends TagFilter
    case class Equals(key: String, value: String)    extends TagFilter
    case class In(key: String, values: Set[String])  extends TagFilter
    case class NotEquals(key: String, value: String) extends TagFilter
  }

  enum SortBy {
    case CreatedAsc, CreatedDesc, UpdatedAsc, UpdatedDesc, WakeupAsc, WakeupDesc
  }

  case class Result(
      id: WorkflowInstanceId,
      status: ExecutionStatus,
      createdAt: Instant,
      updatedAt: Instant,
      tags: Map[String, String],
      wakeupAt: Option[Instant],
  )
}
