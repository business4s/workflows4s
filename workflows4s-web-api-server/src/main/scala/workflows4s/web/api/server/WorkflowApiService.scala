package workflows4s.web.api.server

import cats.effect.IO
import io.circe.Json
import workflows4s.web.api.model.*

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def deliverSignal(request: SignalRequest): IO[Json]
}
