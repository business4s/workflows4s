package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Encoder
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
      entry        <- findEntry(definitionId)
      progress     <- getRealProgress(entry, instanceId)
      mermaidString = MermaidRenderer.renderWorkflow(progress).render
    } yield mermaidString

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def progressToStatus(progress: WIOExecutionProgress[?]): InstanceStatus =
    progress.result match {
      case Some(result) =>
        result.value match {
          case Right(_) => InstanceStatus.Completed
          case Left(_)  => InstanceStatus.Failed
        }
      case None         => InstanceStatus.Running
    }

  private def getRealInstance[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WorkflowInstance] = {
    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
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

    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      progress         <- workflowInstance.getProgress
    } yield progress
  }

  private def getRealInstanceProgressJson[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[ProgressResponse] = {
    for {
      progress    <- getRealProgress(entry, instanceId)
      progressJson = io.circe.Json.obj(
                       "_type"       -> io.circe.Json.fromString("WIOExecutionProgress"),
                       "state"       -> io.circe.Json.fromString(progress.toString),
                       "isCompleted" -> io.circe.Json.fromBoolean(progress.result.isDefined),
                       "steps"       -> io.circe.Json.arr(),
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
  )
}
