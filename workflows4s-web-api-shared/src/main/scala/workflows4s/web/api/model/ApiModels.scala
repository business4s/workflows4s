package workflows4s.web.api.model

import io.circe.Codec
import java.time.Instant

case class WorkflowDefinition(
  id: String,
  name: String,
  description: Option[String],
  version: String,
  createdAt: Instant,
) derives Codec.AsObject

 
case class WorkflowInstance(
  id: String,
  definitionId: String,
  status: InstanceStatus,
  createdAt: Instant,
  updatedAt: Instant,
  currentStep: Option[String],
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed, Paused
}

case class InstancesFilter(
  status: Option[InstanceStatus] = None,
  createdAfter: Option[Instant] = None,
  createdBefore: Option[Instant] = None,
  limit: Option[Int] = None,
  offset: Option[Int] = None,
) derives Codec.AsObject

case class PaginatedResponse[T](
  items: List[T],
  total: Int,
  hasMore: Boolean,
) derives Codec.AsObject