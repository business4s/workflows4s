package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Encoder
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.WorkflowContext

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?]],
) extends WorkflowApiService {

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(workflowEntries.map(e => WorkflowDefinition(e.id, e.name)))

  def getDefinition(id: String): IO[WorkflowDefinition] =
    findEntry(id).map(e => WorkflowDefinition(e.id, e.name))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      entry    <- findEntry(definitionId)
      instance <- getRealInstance(entry, instanceId)
    } yield instance

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def getRealInstance[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WorkflowInstance] = {
    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
      mermaid           = MermaidRenderer.renderWorkflow(progress)
    } yield WorkflowInstance(
      id = instanceId,
      definitionId = entry.id,
      state = Some(entry.stateEncoder(currentState)),
      mermaidUrl = mermaid.toViewUrl,
      mermaidCode = mermaid.render,
    )
  }

}

object RealWorkflowService {
  case class WorkflowEntry[Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx],
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
  )
}
