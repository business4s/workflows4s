package workflows4s.web.ui.http

import cats.effect.IO
import sttp.client4.impl.cats.FetchCatsBackend
import workflows4s.web.api.endpoints.WorkflowEndpoints
import sttp.tapir.client.sttp4.SttpClientInterpreter
import workflows4s.web.api.model.{WorkflowDefinition, WorkflowInstance}

object Http {

  private val backend     = FetchCatsBackend[IO]()
  private val interpreter = SttpClientInterpreter()

  def getInstance(workflowId: String, instanceId: String): IO[WorkflowInstance] = {
    val request = interpreter.toRequestThrowErrors(WorkflowEndpoints.getInstance, None)(workflowId, instanceId)
    backend.send(request).map(_.body)
  }

  def listDefinitions: IO[List[WorkflowDefinition]] = {
    val request = interpreter.toRequestThrowErrors(WorkflowEndpoints.listDefinitions, None)(())
    backend.send(request).map(_.body)
  }

}
