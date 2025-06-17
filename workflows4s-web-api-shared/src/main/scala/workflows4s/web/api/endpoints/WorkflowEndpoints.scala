 package workflows4s.web.api.endpoints

import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import workflows4s.web.api.model.*
 
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

  //Commented out as it is not needed for now.
  // // GET /api/v1/definitions/{id}/instances?status=Running&limit=10&offset=0
  // val listInstances: PublicEndpoint[(String, Option[String], Option[Long], Option[Long], Option[Int], Option[Int]), String, PaginatedResponse[WorkflowInstance], Any] =
  //   baseEndpoint
  //     .get
  //     .in("definitions" / path[String]("id") / "instances")
  //     .in(query[Option[String]]("status"))
  //     .in(query[Option[Long]]("createdAfter"))
  //     .in(query[Option[Long]]("createdBefore"))
  //     .in(query[Option[Int]]("limit"))
  //     .in(query[Option[Int]]("offset"))
  //     .out(jsonBody[PaginatedResponse[WorkflowInstance]])
  //     .description("List workflow instances with filtering and pagination")

  // POST /api/v1/definitions/{workflowId}/instances/test

  val createTestInstanceEndpoint: PublicEndpoint[String, String, WorkflowInstance, Any] =
    baseEndpoint
      .post
      .in("definitions" / path[String]("workflowId") / "instances" / "test")
      .out(jsonBody[WorkflowInstance])
      .description("Create a test workflow instance")

  // GET /api/v1/definitions/{defId}/instances/{instanceId}
  val getInstance: PublicEndpoint[(String, String), String, WorkflowInstance, Any] =
    baseEndpoint
      .get
      .in("definitions" / path[String]("defId") / "instances" / path[String]("instanceId"))
      .out(jsonBody[WorkflowInstance])
      .description("Get specific workflow instance by definition ID and instance ID")

  val allEndpoints = List(listDefinitions, getDefinition, getInstance, createTestInstanceEndpoint)
}