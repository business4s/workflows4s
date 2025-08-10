package workflows4s.web.api.service

import workflows4s.web.api.model.*
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}
import workflows4s.wio.model.WIOExecutionProgressCodec.given

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def getProgress(definitionId: String, instanceId: String): IO[Json]
  def getProgressAsMermaid(definitionId: String, instanceId: String): IO[String]
}

class MockWorkflowApiService extends WorkflowApiService {
  private val mockDefinitions = List(
    WorkflowDefinition("withdrawal-v1", "Withdrawal Workflow"),
    WorkflowDefinition("approval-v1",   "Approval Workflow"),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "withdrawal-v1", status = InstanceStatus.Running,   state = Some(Json.fromString("validation"))),
    WorkflowInstance("inst-2", "withdrawal-v1", status = InstanceStatus.Completed, state = None),
    WorkflowInstance("inst-3", "withdrawal-v1", status = InstanceStatus.Running,   state = Some(Json.fromString("approval"))),
    WorkflowInstance("inst-4", "withdrawal-v1", status = InstanceStatus.Failed,    state = Some(Json.fromString("processing"))),
    WorkflowInstance("inst-5", "approval-v1",   status = InstanceStatus.Running,   state = Some(Json.fromString("review"))),
    WorkflowInstance("inst-6", "approval-v1",   status = InstanceStatus.Completed, state = None),
  )

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(mockDefinitions)

  def getDefinition(id: String): IO[WorkflowDefinition] =
    IO.fromOption(mockDefinitions.find(_.id == id))(new Exception(s"Definition not found: $id"))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      _        <- IO.fromOption(mockDefinitions.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
      instance <- IO.fromOption(mockInstances.find(i => i.id == instanceId && i.definitionId == definitionId))(
                    new Exception(s"Instance not found: $instanceId")
                  )
    } yield instance

  def getProgress(definitionId: String, instanceId: String): IO[Json] =
    for {
      inst <- getInstance(definitionId, instanceId)
      prog: WIOExecutionProgress[String] = inst.status match {
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
            WIOExecutionProgress.RunIO(WIOMeta.RunIO(Some("Processing"), None), Some(Left(())))
          ))
      }
    } yield prog.asJson

  def getProgressAsMermaid(definitionId: String, instanceId: String): IO[String] = IO.pure {
    """flowchart TD
A[Start] --> B{Processing?}
B -->|Yes| C[OK]
C --> D[End]
B -->|No| E[Failed]
E --> D
"""
  }
}