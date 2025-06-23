 package workflows4s.web.api.server

import cats.effect.IO
import cats.syntax.either.*
import io.circe.Json
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.model.{InstanceStatus, WorkflowInstance}
import workflows4s.web.api.service.WorkflowApiService

class WorkflowServerEndpoints(workflowService: WorkflowApiService) {

  private def createTestInstanceLogic(workflowId: String): Either[String, WorkflowInstance] = {
    val state = Json.obj(
      "workflow_id" -> Json.fromString(workflowId),
      "timestamp"   -> Json.fromString(java.time.Instant.now().toString),
      "test_data"   -> Json.fromBoolean(true),
      "status"      -> Json.fromString("initialized"),
    )

    Right(
      WorkflowInstance(
        id = s"test-instance-${System.currentTimeMillis()}",
        definitionId = workflowId,
        status = InstanceStatus.Running,
        state = Some(state),
      ),
    )
  }

  val endpoints: List[ServerEndpoint[Any, IO]] = List(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => workflowService.listDefinitions().attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getDefinition.serverLogic(workflowId => workflowService.getDefinition(workflowId).attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) => {
      if (instanceId.startsWith("test-instance-")) {
        IO.pure(createTestInstanceLogic(workflowId))
      } else {
        workflowService.getInstance(workflowId, instanceId).attempt.map(_.leftMap(_.getMessage))
      }
    }),
    WorkflowEndpoints.createTestInstanceEndpoint.serverLogic(workflowId => {
      IO.pure(createTestInstanceLogic(workflowId))
    }),
  )
}