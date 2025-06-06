package workflows4s.web.api.endpoints

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import workflows4s.web.api.model.*
import java.time.Instant

object WorkflowEndpoints {

  private val baseEndpoint = endpoint
    .in("api" / "v1")
    .errorOut(stringBody)

  // GET /api/v1/definitions
  val listDefinitions: PublicEndpoint[Unit, String, List[WorkflowDefinition], Any] =
    baseEndpoint
      .get
      .in("definitions")
      .out(jsonBody[List[WorkflowDefinition]])
      .description("List all workflow definitions")

  // GET /api/v1/definitions/{id}
  val getDefinition: PublicEndpoint[String, String, WorkflowDefinition, Any] =
    baseEndpoint
      .get
      .in("definitions" / path[String]("id"))
      .out(jsonBody[WorkflowDefinition])
      .description("Get workflow definition by ID")

  // GET /api/v1/definitions/{defId}/instances/{instanceId}
  val getInstance: PublicEndpoint[(String, String), String, WorkflowInstance, Any] =
    baseEndpoint
      .get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId"))
      .out(jsonBody[WorkflowInstance])
      .description("Get specific workflow instance by definition ID and instance ID")

  val allEndpoints = List(listDefinitions, getDefinition, getInstance)
}