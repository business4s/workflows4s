package workflows4s.web.api.service

import cats.effect.IO
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.WorkflowContext
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.model.WIOExecutionProgressCodec.given

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?, ?]],
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

  def getProgress(definitionId: String, instanceId: String): IO[Json] =
    for {
      entry <- findEntry(definitionId)
      json  <- getRealInstanceProgressJson(entry, instanceId)
    } yield json

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?, ?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def progressToStatus(progress: WIOExecutionProgress[?]): InstanceStatus =
    progress.result match {
      case Some(Right(_)) => InstanceStatus.Completed
      case Some(Left(_))  => InstanceStatus.Failed
      case None           => InstanceStatus.Running
    }

  private def getRealInstance[WorkflowId, Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[WorkflowId, Ctx],
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

  private def getRealInstanceProgressJson[WorkflowId, Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[WorkflowId, Ctx],
      instanceId: String,
  ): IO[Json] = {
    val parsedId = entry.parseId(instanceId)
    for {
      workflowInstance <- entry.runtime.createInstance(parsedId)
      progress         <- workflowInstance.getProgress // WIOExecutionProgress[WCState[Ctx]]
      progressAsString  = progress.map(st => Some(st.toString)) // map: State => Option[String]
    } yield progressAsString.asJson
  }
}

object RealWorkflowService {
  case class WorkflowEntry[WorkflowId, Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx, WorkflowId],
      parseId: String => WorkflowId,
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
  )
}