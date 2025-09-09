package workflows4s.web.api.service

import cats.effect.IO
import workflows4s.web.api.model.*
import io.circe.Json

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def deliverSignal(request: SignalRequest): IO[Json]
}

class MockWorkflowApiService extends WorkflowApiService {

  private val mockDefinitions = List(
    WorkflowDefinition("course-registration-v1", "Course Registration", Some("Student course registration workflow")),
    WorkflowDefinition("pull-request-v1", "Pull Request", Some("Code review workflow")),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "course-registration-v1", state = Some(Json.fromString("browsing")), "", "", Seq()),
    WorkflowInstance("inst-2", "course-registration-v1", state = Some(Json.fromString("registered")), "", "", Seq()),
    WorkflowInstance("inst-3", "course-registration-v1", state = Some(Json.fromString("validation_failed")), "", "", Seq()),
    WorkflowInstance("inst-4", "course-registration-v1", state = Some(Json.fromString("processing")), "", "", Seq()),
    WorkflowInstance("inst-5", "pull-request-v1", state = Some(Json.fromString("review")), "", "", Seq()),
    WorkflowInstance("inst-6", "pull-request-v1", state = None, "", "", Seq()),
  )

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(mockDefinitions)

  def getDefinition(id: String): IO[WorkflowDefinition] =
    IO.fromOption(mockDefinitions.find(_.id == id))(new Exception(s"Definition not found: $id"))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      _        <- IO.fromOption(mockDefinitions.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
      instance <- IO.fromOption(mockInstances.find(i => i.id == instanceId && i.templateId == definitionId))(
                    new Exception(s"Instance not found: $instanceId"),
                  )
    } yield instance

  override def deliverSignal(request: SignalRequest): IO[Json] = ???
}
