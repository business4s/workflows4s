package workflows4s.web.api.service

import cats.effect.IO
import io.circe.{Encoder, Json}
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.{WCState, WorkflowContext}
import workflows4s.wio.model.WIOExecutionProgress

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

  def getProgress(definitionId: String, instanceId: String): IO[ProgressResponse] =
    for {
      entry <- findEntry(definitionId)
      json  <- getRealInstanceProgressJson(entry, instanceId)
    } yield json

  def getProgressAsMermaid(definitionId: String, instanceId: String): IO[String] =
    for {
      entry    <- findEntry(definitionId)
      progress <- getRealProgress(entry, instanceId)
    } yield MermaidRenderer.renderWorkflow(progress).toString

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def progressToStatus(progress: WIOExecutionProgress[?]): InstanceStatus =
    progress.result match {
      case Some(result) => result.value match {
        case Right(_) => InstanceStatus.Completed
        case Left(_)  => InstanceStatus.Failed
      }
      case None => InstanceStatus.Running
    }

  private def getRealInstance[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WorkflowInstance] = {
    val parsedId = entry.parseId(instanceId)
    for {
      workflowInstance <- entry.runtime.createInstance(parsedId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
    } yield WorkflowInstance(
      id = instanceId,
      definitionId = entry.id,
      status = progressToStatus(progress),
      state = Some(entry.stateEncoder(currentState)),
    )
  }

  private def getRealProgress[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WIOExecutionProgress[WCState[Ctx]]] = {
    val parsedId = entry.parseId(instanceId)
    for {
      workflowInstance <- entry.runtime.createInstance(parsedId)
      progress         <- workflowInstance.getProgress
    } yield progress
  }

  private def getRealInstanceProgressJson[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[ProgressResponse] = {
    for {
      progress <- getRealProgress(entry, instanceId) // WIOExecutionProgress[WCState[Ctx]]
      // Convert to Json manually since complex codec doesn't work yet
      progressJson = Json.obj(
        "_type" -> Json.fromString("WIOExecutionProgress"),
        "state" -> Json.fromString(progress.toString), // Simple string representation for now
        "isCompleted" -> Json.fromBoolean(progress.result.isDefined),
        "steps" -> Json.arr() // Empty for now, will be enhanced later
      )
    } yield ProgressResponse(progressJson)
  }
}

object RealWorkflowService {
  case class WorkflowEntry[Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx],
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
      parseId: String => String = identity,
  )
}