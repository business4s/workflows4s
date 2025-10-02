package workflows4s.web.ui

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import io.circe.Json
import sttp.client4.impl.cats.FetchCatsBackend
import sttp.tapir.PublicEndpoint
import sttp.tapir.client.sttp4.SttpClientInterpreter
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.model.{SignalRequest, WorkflowDefinition, WorkflowInstance, WorkflowSearchResult}
import workflows4s.web.ui.util.UIConfig

object Http {
  private val backend     = FetchCatsBackend[IO]()
  private val interpreter = SttpClientInterpreter()

  def getInstance(workflowId: String, instanceId: String): IO[WorkflowInstance] = send(WorkflowEndpoints.getInstance)((workflowId, instanceId))
  def listDefinitions: IO[List[WorkflowDefinition]] = send(WorkflowEndpoints.listDefinitions)(())
  def sendSignal(req: SignalRequest): IO[Json] = send(WorkflowEndpoints.deliverSignal)(req)
  def searchWorkflows(templateId: String): IO[List[WorkflowSearchResult]] = send(WorkflowEndpoints.searchWorkflows)(templateId)

  private def send[I, E, O](e: PublicEndpoint[I, E, O, Any]): I => IO[O] = { input =>
    for {
      uiConfig <- UIConfig.get
      request = interpreter.toRequestThrowErrors(e, uiConfig.apiUrl.some)(input)
      resp <- backend.send(request)
    } yield resp.body
  }

}
