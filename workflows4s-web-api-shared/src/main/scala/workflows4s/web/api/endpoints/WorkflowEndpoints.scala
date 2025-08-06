package workflows4s.web.api.endpoints

import sttp.tapir.*
import sttp.tapir.json.circe.*
import sttp.tapir.generic.auto.*
import workflows4s.web.api.model.*
import workflows4s.wio.model.{WIOExecutionProgress, WIOModel}
import workflows4s.wio.model.WIOExecutionProgressCodec.given
import workflows4s.wio.model.WIOModel.given

object WorkflowEndpoints {

  // For now, simple schemas to get it compiling
  given Schema[WIOExecutionProgress[String]] = Schema.string
  given Schema[WIOModel] = Schema.string

  val listDefinitions: PublicEndpoint[Unit, String, List[WorkflowDefinition], Any] =
    endpoint
      .get
      .in("api" / "v1" / "definitions")
      .out(jsonBody[List[WorkflowDefinition]])
      .errorOut(stringBody)
      .description("List all workflow definitions")

  val getDefinition: PublicEndpoint[String, String, WorkflowDefinition, Any] =
    endpoint
      .get
      .in("api" / "v1" / "definitions" / path[String]("defId"))
      .out(jsonBody[WorkflowDefinition])
      .errorOut(stringBody)
      .description("Get workflow definition by ID")

  val getDefinitionModel: PublicEndpoint[String, String, WIOModel, Any] =
    endpoint
      .get
      .in("api" / "v1" / "definitions" / path[String]("defId") / "model")
      .errorOut(stringBody)
      .out(jsonBody[WIOModel])
      .description("Get workflow definition model by definition ID")

  val getInstance: PublicEndpoint[(String, String), String, WorkflowInstance, Any] =
    endpoint
      .get
      .in("api" / "v1" / "definitions" / path[String]("defId") / "instances" / path[String]("instanceId"))
      .out(jsonBody[WorkflowInstance])
      .errorOut(stringBody)
      .description("Get workflow instance by definition ID and instance ID")

  val getInstanceProgress: PublicEndpoint[(String, String), String, WIOExecutionProgress[String], Any] =
    endpoint
      .get
      .in("api" / "v1" / "definitions" / path[String]("defId") / "instances" / path[String]("instanceId") / "progress")
      .errorOut(stringBody)
      .out(jsonBody[WIOExecutionProgress[String]])
      .description("Get workflow instance progress by definition ID and instance ID")

  val createTestInstanceEndpoint: PublicEndpoint[String, String, WorkflowInstance, Any] = endpoint.post
    .in("api" / "v1" / "definitions" / path[String]("workflowId") / "instances" / "test")
    .errorOut(stringBody)
    .out(jsonBody[WorkflowInstance])
    .description("Create a test instance for the given workflow")
}