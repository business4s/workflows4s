package workflows4s.web.api.server

import io.circe.Json
import workflows4s.web.api.model.*

trait WorkflowApiService[F[_]] {
  def listDefinitions(): F[List[WorkflowDefinition]]
  def getDefinition(id: String): F[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): F[WorkflowInstance]
  def deliverSignal(request: SignalRequest): F[Json]
  def searchWorkflows(templateId: String): F[List[WorkflowSearchResult]]
}
