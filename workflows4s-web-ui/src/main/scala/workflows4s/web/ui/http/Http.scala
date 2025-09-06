package workflows4s.web.ui.http

import cats.effect.IO
import sttp.client4.*
import sttp.client4.impl.cats.FetchCatsBackend
import sttp.tapir.client.sttp4.SttpClientInterpreter
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.model.{WorkflowDefinition, WorkflowInstance}

object Http {
  private val backend     = FetchCatsBackend[IO]()
  private val baseUrl     = "http://localhost:8081"
  private val interpreter = SttpClientInterpreter()

  def getInstance(workflowId: String, instanceId: String): IO[WorkflowInstance] = {
    val request = interpreter.toRequestThrowErrors(WorkflowEndpoints.getInstance, Some(uri"$baseUrl"))((workflowId, instanceId))
    backend.send(request).map(_.body)
  }

  def listDefinitions: IO[List[WorkflowDefinition]] = {
    val request = interpreter.toRequestThrowErrors(WorkflowEndpoints.listDefinitions, Some(uri"$baseUrl"))(())
    backend.send(request).map(_.body)
  }

}
