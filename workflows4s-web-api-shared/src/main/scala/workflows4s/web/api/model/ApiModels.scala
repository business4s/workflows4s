package workflows4s.web.api.model

import io.circe.{Codec, Json}
import io.circe.syntax.*
import sttp.apispec
import sttp.apispec.circe.*
import sttp.tapir
import sttp.tapir.json.circe.*

import java.time.Instant

case class WorkflowDefinition(
    id: String,
    name: String,
    description: Option[String] = None,
    mermaidUrl: String,
    mermaidCode: String,
) derives Codec.AsObject,
      tapir.Schema

case class WorkflowInstance(
    id: String,
    templateId: String,
    state: io.circe.Json,
    mermaidUrl: String,
    mermaidCode: String,
    expectedSignals: Seq[Signal],
) derives Codec.AsObject,
      tapir.Schema

case class Signal(
    id: String,
    name: String,
    requestSchema: Option[apispec.Schema],
) derives Codec.AsObject,
      tapir.Schema

given sttp.tapir.Schema[apispec.Schema] = schemaForCirceJson.map(_.as[apispec.Schema].toOption)(_.asJson)

case class SignalRequest(
    templateId: String,
    instanceId: String,
    signalId: String,
    signalRequest: Json,
)

enum ExecutionStatus derives Codec, tapir.Schema {
  case Running, Awaiting, Finished
}

// Search API models
case class WorkflowSearchResult(
    templateId: String,
    instanceId: String,
    status: ExecutionStatus,
    createdAt: Instant,
    updatedAt: Instant,
    wakeupAt: Option[Instant]
) derives Codec.AsObject,
      tapir.Schema
