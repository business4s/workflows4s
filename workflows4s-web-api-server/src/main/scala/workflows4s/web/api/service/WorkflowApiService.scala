package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Json
import workflows4s.web.api.model.*
import workflows4s.wio.model.{WIOExecutionProgress, WIOModel, WIOMeta}

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getDefinitionModel(id: String): IO[WIOModel]
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

  override def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(mockDefinitions)

  override def getDefinition(id: String): IO[WorkflowDefinition] =
    IO.fromOption(mockDefinitions.find(_.id == id))(new Exception(s"Definition not found: $id"))

  override def getDefinitionModel(id: String): IO[WIOModel] = {
    for {
      _ <- getDefinition(id)
    } yield WIOModel.RunIO(WIOMeta.RunIO(Some(s"Mock Model for $id"), None))
  }

  override def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] = {
    for {
      _ <- getDefinition(definitionId)
      instance <- IO.fromOption(mockInstances.find(_.id == instanceId))(new Exception(s"Instance not found: $instanceId"))
    } yield instance
  }

  override def getProgress(definitionId: String, instanceId: String): IO[WIOExecutionProgress[String]] = {
    for {
      instance <- getInstance(definitionId, instanceId)
    } yield {
      // Create a mock progress based on instance status
      instance.status match {
        case InstanceStatus.Running => 
          WIOExecutionProgress.Sequence(Seq(
            WIOExecutionProgress.Pure(WIOMeta.Pure(Some("Initialize"), None), Some(Right("initialized"))),
            WIOExecutionProgress.RunIO(WIOMeta.RunIO(Some("Processing"), None), None)
          ))
        case InstanceStatus.Completed =>
          WIOExecutionProgress.Sequence(Seq(
            WIOExecutionProgress.Pure(WIOMeta.Pure(Some("Initialize"), None), Some(Right("initialized"))),
            WIOExecutionProgress.RunIO(WIOMeta.RunIO(Some("Processing"), None), Some(Right("completed")))
          ))
        case InstanceStatus.Failed =>
          WIOExecutionProgress.Sequence(Seq(
            WIOExecutionProgress.Pure(WIOMeta.Pure(Some("Initialize"), None), Some(Right("initialized"))),
            WIOExecutionProgress.RunIO(WIOMeta.RunIO(Some("Processing"), None), Some(Left("error")))
          ))
      }
    }
  }
}