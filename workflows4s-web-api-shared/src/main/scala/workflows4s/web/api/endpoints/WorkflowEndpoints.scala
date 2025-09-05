package workflows4s.web.api.endpoints

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import workflows4s.web.api.model.{ProgressResponse, WorkflowDefinition, WorkflowInstance}

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

  val getProgress: PublicEndpoint[(String, String), String, ProgressResponse, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId") / "progress")
      .out(jsonBody[ProgressResponse])
      .description("Get workflow instance progress")

  val createTestInstance: PublicEndpoint[String, String, WorkflowInstance, Any] =
    baseEndpoint.post
      .in("definitions" / path[String]("defId") / "test-instance")
      .out(jsonBody[WorkflowInstance])
      .description("Create a test instance for the workflow")
}
