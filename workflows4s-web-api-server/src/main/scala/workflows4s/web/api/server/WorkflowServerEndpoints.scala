package workflows4s.web.api.server

import cats.effect.IO
import cats.syntax.either.*
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.model.*
import workflows4s.web.api.service.WorkflowApiService
import sttp.tapir.server.ServerEndpoint

class WorkflowServerEndpoints(workflowService: WorkflowApiService) {

  private def createTestInstanceLogic(workflowId: String): Either[String, WorkflowInstance] = {
    val testInstanceId = s"test-instance-${System.currentTimeMillis()}"
    Right(
      WorkflowInstance(
        id = testInstanceId,
        definitionId = workflowId,
        status = InstanceStatus.Running,
        state = None,
      ),
    )
  }

  val endpoints: List[ServerEndpoint[Any, IO]] = List(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => workflowService.listDefinitions().attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getDefinition.serverLogic(workflowId => workflowService.getDefinition(workflowId).attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) => {
      if instanceId.startsWith("test-instance-") then {
        IO.pure(createTestInstanceLogic(workflowId))
      } else {
        workflowService.getInstance(workflowId, instanceId).attempt.map(_.leftMap(_.getMessage))
      }
    }),
    WorkflowEndpoints.getProgress.serverLogic { case (defId, instanceId) =>
      workflowService.getProgress(defId, instanceId).attempt.map(_.leftMap(_.getMessage))
    },
    WorkflowEndpoints.createTestInstance.serverLogic(workflowId => {
      IO.pure(createTestInstanceLogic(workflowId))
    }),
  )
}
