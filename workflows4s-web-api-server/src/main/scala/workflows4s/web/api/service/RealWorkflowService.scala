package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.WorkflowContext
import workflows4s.wio.model.{WIOExecutionProgress, WIOModel, WIOMeta}

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?, ?]],
) extends WorkflowApiService {

  override def listDefinitions(): IO[List[WorkflowDefinition]] = {
    val definitions = workflowEntries.map(entry =>
      WorkflowDefinition(
        id = entry.id,
        name = entry.name,
      ),
    )
    IO.pure(definitions)
  }

  override def getDefinition(id: String): IO[WorkflowDefinition] = {
    for {
      entry <- findEntry(id)
    } yield WorkflowDefinition(id = entry.id, name = entry.name)
  }

  override def getDefinitionModel(id: String): IO[WIOModel] = {
    // Simply return a mock model for now to get it compiling
    for {
      _ <- findEntry(id)
    } yield WIOModel.RunIO(WIOMeta.RunIO(Some(s"Model for $id"), None))
  }

  override def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] = {
    for {
      entry    <- findEntry(definitionId)
      instance <- getRealInstance(entry, instanceId)
    } yield instance
  }

  override def getProgress(definitionId: String, instanceId: String): IO[WIOExecutionProgress[String]] = {
    for {
      entry    <- findEntry(definitionId)
      progress <- getRealInstanceProgress(entry, instanceId)
    } yield progress
  }

  // Helper method to avoid repetition as suggested by Voytek
  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?, ?]] = {
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
  }

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
    val parsedInstanceId = entry.parseId(instanceId)
    for {
      workflowInstance <- entry.runtime.createInstance(parsedInstanceId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
    } yield WorkflowInstance(
      id = instanceId,
      definitionId = entry.id,
      status = progressToStatus(progress),
      state = Some(entry.stateEncoder(currentState)),
    )
  }

  private def getRealInstanceProgress[WorkflowId, Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[WorkflowId, Ctx],
      instanceId: String,
  ): IO[WIOExecutionProgress[String]] = {
    val parsedInstanceId = entry.parseId(instanceId)
    for {
      workflowInstance <- entry.runtime.createInstance(parsedInstanceId)
      progress         <- workflowInstance.getProgress
    } yield progress.map(state => Some(entry.stateEncoder(state).noSpaces))
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