package workflows4s.web.api.service

import cats.effect.IO
import workflows4s.web.api.model.*
import io.circe.Json

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
}

class MockWorkflowApiService extends WorkflowApiService {

  private val mockDefinitions = List(
    WorkflowDefinition("course-registration-v1", "Course Registration", Some("Student course registration workflow")),
    WorkflowDefinition("pull-request-v1", "Pull Request", Some("Code review workflow")),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "course-registration-v1", state = Some(Json.fromString("browsing")), "", ""),
    WorkflowInstance("inst-2", "course-registration-v1", state = Some(Json.fromString("registered")), "", ""),
    WorkflowInstance("inst-3", "course-registration-v1", state = Some(Json.fromString("validation_failed")), "", ""),
    WorkflowInstance("inst-4", "course-registration-v1", state = Some(Json.fromString("processing")), "", ""),
    WorkflowInstance("inst-5", "pull-request-v1", state = Some(Json.fromString("review")), "", ""),
    WorkflowInstance("inst-6", "pull-request-v1", state = None, "", ""),
  )

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(mockDefinitions)

  def getDefinition(id: String): IO[WorkflowDefinition] =
    IO.fromOption(mockDefinitions.find(_.id == id))(new Exception(s"Definition not found: $id"))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      _        <- IO.fromOption(mockDefinitions.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
      instance <- IO.fromOption(mockInstances.find(i => i.id == instanceId && i.definitionId == definitionId))(
                    new Exception(s"Instance not found: $instanceId"),
                  )
    } yield instance
}
