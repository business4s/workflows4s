package workflows4s.web.api.model

import io.circe.Codec
import java.time.Instant

case class WorkflowDefinition(
  id: String,
  name: String,
) derives Codec.AsObject

case class WorkflowInstance(
  id: String,
  definitionId: String,
  state: String,
) derives Codec.AsObject