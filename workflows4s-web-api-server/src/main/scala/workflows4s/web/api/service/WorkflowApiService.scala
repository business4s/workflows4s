package workflows4s.web.api.service

import workflows4s.web.api.model.*
import cats.effect.IO
import io.circe.Json
import workflows4s.wio.model.WIOExecutionProgress

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def getProgress(definitionId: String, instanceId: String): IO[WIOExecutionProgress[String]]
}

class MockWorkflowApiService extends WorkflowApiService {

  private val mockDefinitions = List(
    WorkflowDefinition(
      id = "withdrawal-v1",
      name = "Withdrawal Workflow",
    ),
    WorkflowDefinition(
      id = "approval-v1",
      name = "Approval Workflow",
    ),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "withdrawal-v1", status = InstanceStatus.Running, state = Some(Json.fromString("validation"))),
    WorkflowInstance("inst-2", "withdrawal-v1", status = InstanceStatus.Completed, state = None),
    WorkflowInstance("inst-3", "withdrawal-v1", status = InstanceStatus.Running, state = Some(Json.fromString("approval"))),
    WorkflowInstance("inst-4", "withdrawal-v1", status = InstanceStatus.Failed, state = Some(Json.fromString("processing"))),
    WorkflowInstance("inst-5", "approval-v1", status = InstanceStatus.Running, state = Some(Json.fromString("review"))),
    WorkflowInstance("inst-6", "approval-v1", status = InstanceStatus.Completed, state = None),
  )

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(mockDefinitions)

  def getDefinition(id: String): IO[WorkflowDefinition] =
    IO.fromOption(mockDefinitions.find(_.id == id))(new Exception(s"Definition not found: $id"))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] = {
    for {
      _ <- getDefinition(definitionId)
      instance <- IO.fromOption(mockInstances.find(_.id == instanceId))(new Exception(s"Instance not found: $instanceId"))
    } yield instance
  }

  override def getProgress(definitionId: String, instanceId: String): IO[WIOExecutionProgress[String]] =
    IO.raiseError(new NotImplementedError("getProgress is not implemented for MockWorkflowApiService"))

}