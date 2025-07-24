package workflows4s.web.ui.models

import io.circe.{Codec, Json}

final case class WorkflowDefinition(
    id: String,
    name: String,
) derives Codec.AsObject

final case class WorkflowInstance(
    id: String,
    definitionId: String,
    status: InstanceStatus,
    state: Option[Json] = None,
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed, Paused
}
