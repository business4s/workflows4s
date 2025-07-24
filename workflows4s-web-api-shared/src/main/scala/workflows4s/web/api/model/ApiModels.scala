package workflows4s.web.api.model

import io.circe.{Codec, Json}

case class WorkflowDefinition(
    id: String,
    name: String,
) derives Codec.AsObject

case class WorkflowInstance(
    id: String,
    definitionId: String,
    status: InstanceStatus,
    state: Option[Json] = None,
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed
}
