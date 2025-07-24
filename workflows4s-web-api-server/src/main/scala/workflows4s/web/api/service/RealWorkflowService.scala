package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.WorkflowContext
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.model.WIOExecutionProgress.ExecutedResult

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?]],
) extends WorkflowApiService {

  def listDefinitions(): IO[List[WorkflowDefinition]] = {
    val definitions = workflowEntries.map(entry =>
      WorkflowDefinition(
        id = entry.id,
        name = entry.name,
      ),
    )
    IO.pure(definitions)
  }

  def getDefinition(id: String): IO[WorkflowDefinition] = {
    IO.fromOption(
      workflowEntries
        .find(_.id == id)
        .map(entry => WorkflowDefinition(id = entry.id, name = entry.name)),
    )(new Exception(s"Workflow definition not found: $id"))
  }

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] = {
    for {
      entry    <- IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
      instance <- getRealInstance(entry, instanceId)
    } yield instance
  }

  private def progressToStatus(progress: WIOExecutionProgress[?]): InstanceStatus =
    progress.result match {
      case Some(ExecutedResult(Right(_), _)) => InstanceStatus.Completed
      case Some(ExecutedResult(Left(_), _))  => InstanceStatus.Failed
      case None                              => InstanceStatus.Running
    }

  private def getRealInstance[WorkflowId, Ctx <: WorkflowContext](
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
}

object RealWorkflowService {
  case class WorkflowEntry[Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx],
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
  )
}
