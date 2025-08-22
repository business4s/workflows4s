package workflows4s.web.api.endpoints

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import workflows4s.web.api.model.*

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
      .description("Get workflow definition by ID")

  val getInstance: PublicEndpoint[(String, String), String, WorkflowInstance, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId"))
      .out(jsonBody[WorkflowInstance])
      .description("Get workflow instance by definition ID and instance ID")

  val getInstanceProgress: PublicEndpoint[(String, String), String, ProgressResponse, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId") / "progress")
      .out(jsonBody[ProgressResponse])
      .description("Get workflow instance progress by definition ID and instance ID")

  val getInstanceProgressMermaid: PublicEndpoint[(String, String), String, String, Any] =
    baseEndpoint.get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId") / "progress" / "mermaid")
      .out(stringBody)
      .description("Get workflow instance progress as Mermaid diagram")

  val createTestInstanceEndpoint: PublicEndpoint[String, String, WorkflowInstance, Any] =
    baseEndpoint.post
      .in("definitions" / path[String]("defId") / "test-instance")
      .out(jsonBody[WorkflowInstance])
      .description("Create a test instance for a workflow")

  val allEndpoints = List(
    listDefinitions,
    getDefinition,
    getInstance,
    getInstanceProgress,
    getInstanceProgressMermaid,
    createTestInstanceEndpoint,
  )
}
