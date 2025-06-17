package workflows4s.web.api.model

import io.circe.Codec
import io.circe.Json

case class WorkflowDefinition(
  id: String,
  name: String,
) derives Codec.AsObject

case class WorkflowInstance(
  id: String,
  definitionId: String,
  currentStep: Option[String],
  state: Option[Json] = None   
) derives Codec.AsObject
 
 