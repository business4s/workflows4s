package workflows4s.web.api.server

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.service.WorkflowApiService

class WorkflowApiServer(workflowService: WorkflowApiService) {

  val routes: List[ServerEndpoint[Any, IO]] = {
    val serverEndpoints = new WorkflowServerEndpoints(workflowService)
    serverEndpoints.endpoints
  }
}