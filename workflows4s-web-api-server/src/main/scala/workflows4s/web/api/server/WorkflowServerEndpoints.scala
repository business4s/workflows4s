package workflows4s.web.api.server

import cats.effect.IO
import cats.implicits.toBifunctorOps
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.endpoints.WorkflowEndpoints

class WorkflowServerEndpoints(workflowService: WorkflowApiService) {

  val endpoints: List[ServerEndpoint[Any, IO]] = List(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => workflowService.listDefinitions().attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getDefinition.serverLogic(workflowId => workflowService.getDefinition(workflowId).attempt.map(_.leftMap(_.getMessage))),
    WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) =>
      workflowService.getInstance(workflowId, instanceId).attempt.map(_.leftMap(_.getMessage)),
    ),
    WorkflowEndpoints.deliverSignal.serverLogic(request => workflowService.deliverSignal(request).attempt.map(_.leftMap(_.getMessage))),
  )

}
