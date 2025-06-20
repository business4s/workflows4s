 package workflows4s.web.api.server

import sttp.tapir.server.ServerEndpoint
import cats.effect.IO
import workflows4s.web.api.endpoints.WorkflowEndpoints  
import workflows4s.web.api.service.RealWorkflowService 
import workflows4s.web.api.model.WorkflowInstance       
import io.circe.Json                                   

class WorkflowServerEndpoints(workflowService: RealWorkflowService) {

  private def createTestInstanceLogic(workflowId: String): Either[String, WorkflowInstance] = {
    val state = Json.obj(
      "workflow_id" -> Json.fromString(workflowId),
      "timestamp" -> Json.fromString(java.time.Instant.now().toString),
      "test_data" -> Json.fromBoolean(true),
      "status" -> Json.fromString("initialized")
    )

    Right(WorkflowInstance(
      id = s"test-instance-${System.currentTimeMillis()}",
      definitionId = workflowId,
      state = Some(state)
    ))
  }

  val endpoints: List[ServerEndpoint[Any, IO]] = List(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => workflowService.listDefinitions()),
    WorkflowEndpoints.getDefinition.serverLogic(workflowId => workflowService.getDefinition(workflowId)),
    WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) => {
      if (instanceId.startsWith("test-instance-")) {
        IO.pure(createTestInstanceLogic(workflowId).map(instance => instance.copy(id = instanceId)))
      } else {
        workflowService.getInstance(workflowId, instanceId)
      }
    }),
    WorkflowEndpoints.createTestInstanceEndpoint.serverLogic(workflowId => {
      IO.pure(createTestInstanceLogic(workflowId))
    })
  )
}