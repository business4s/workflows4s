package workflows4s.web.api.server

import cats.effect.Async
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import sttp.tapir.server.ServerEndpoint
import workflows4s.runtime.registry.WorkflowSearch
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.server.WorkflowEntry

object WorkflowServerEndpoints extends StrictLogging {

  def get[F[_]](entries: List[WorkflowEntry[F, ?]], search: Option[WorkflowSearch[F]])(using F: Async[F]): List[ServerEndpoint[Any, F]] = {
    val service = WorkflowApiServiceImpl(entries, search)
    List(
      WorkflowEndpoints.listDefinitions.serverLogic(_ => handleError(service.listDefinitions())),
      WorkflowEndpoints.getDefinition.serverLogic(workflowId => handleError(service.getDefinition(workflowId))),
      WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) => handleError(service.getInstance(workflowId, instanceId))),
      WorkflowEndpoints.deliverSignal.serverLogic(request => handleError(service.deliverSignal(request))),
      WorkflowEndpoints.searchWorkflows.serverLogic { templateId => handleError(service.searchWorkflows(templateId)) },
    )
  }

  private def handleError[F[_]: Async, A](fa: F[A]): F[Either[String, A]] =
    fa.attempt.map {
      case Left(error)  =>
        logger.error("Operation failed", error)
        Left(error.getMessage)
      case Right(value) => Right(value)
    }

}
