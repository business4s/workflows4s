package workflows4s.web.api.model

import io.circe.{Codec, Json}

case class WorkflowDefinition(
    id: String,
    name: String,
    version: Option[String] = None,
    description: Option[String] = None,
) derives Codec.AsObject

case class WorkflowInstance(
    id: String,
    definitionId: String,
    status: InstanceStatus,
    state: Option[Json] = None,
    createdAt: Option[String] = None,
    updatedAt: Option[String] = None,
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed, Paused
}

// For now, use Json until complex codecs are working
case class ProgressResponse(progress: Json) derives Codec.AsObject
