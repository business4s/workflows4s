package workflows4s.web.api.server

import cats.MonadError
import cats.syntax.all.*
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.server.RealWorkflowService.WorkflowEntry

object WorkflowServerEndpoints {

  def get[F[_]](entries: List[WorkflowEntry[F, ?]])(using MonadError[F, Throwable]): List[ServerEndpoint[Any, F]] = {
    val service = RealWorkflowService(entries)
    List(
      WorkflowEndpoints.listDefinitions.serverLogic(_ => service.listDefinitions().attempt.map(_.leftMap(_.getMessage))),
      WorkflowEndpoints.getDefinition.serverLogic(workflowId => service.getDefinition(workflowId).attempt.map(_.leftMap(_.getMessage))),
      WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) =>
        service.getInstance(workflowId, instanceId).attempt.map(_.leftMap(_.getMessage)),
      ),
      WorkflowEndpoints.deliverSignal.serverLogic(request => service.deliverSignal(request).attempt.map(_.leftMap(_.getMessage))),
    )
  }
}
