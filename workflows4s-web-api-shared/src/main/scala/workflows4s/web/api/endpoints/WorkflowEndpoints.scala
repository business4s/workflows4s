package workflows4s.web.api.endpoints

import io.circe.Json
import sttp.tapir.*
import sttp.tapir.json.circe.*
import workflows4s.web.api.model.{SignalRequest, WorkflowDefinition, WorkflowInstance, WorkflowSearchRequest, WorkflowSearchResponse}

object WorkflowEndpoints {

  private val baseEndpoint = endpoint
    .in("api" / "v1")
    .errorOut(stringBody)

  val listDefinitions: PublicEndpoint[Unit, String, List[WorkflowDefinition], Any] =
    baseEndpoint.get
      .in("definitions")
      .out(jsonBody[List[WorkflowDefinition]])
      .description("List all workflow definitions")

  val getDefinition: PublicEndpoint[String, String, WorkflowDefinition, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId"))
      .out(jsonBody[WorkflowDefinition])
      .description("Get workflow definition details")

  val getInstance: PublicEndpoint[(String, String), String, WorkflowInstance, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId"))
      .out(jsonBody[WorkflowInstance])
      .description("Get workflow instance details")

  val deliverSignal: PublicEndpoint[SignalRequest, String, Json, Any] =
    baseEndpoint.post
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId") / path[String]("signalId"))
      .in(jsonBody[Json])
      .out(jsonBody[Json])
      .description("Get workflow instance details")
      .mapInTo[SignalRequest]

  // Search workflows (instances) endpoint
  val searchWorkflows: PublicEndpoint[WorkflowSearchRequest, String, WorkflowSearchResponse, Any] =
    baseEndpoint.post
      .in("workflows" / "search")
      .in(jsonBody[WorkflowSearchRequest])
      .out(jsonBody[WorkflowSearchResponse])
      .description("Search among workflow instances")
}
