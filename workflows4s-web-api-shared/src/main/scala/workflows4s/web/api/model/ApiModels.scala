package workflows4s.web.api.model

import io.circe.Codec

case class WorkflowDefinition(
    id: String,
    name: String,
    description: Option[String] = None,
) derives Codec.AsObject

case class WorkflowInstance(
    id: String,
    definitionId: String,
    state: Option[io.circe.Json] = None,
    mermaidUrl: String,
    mermaidCode: String,
) derives Codec.AsObject
