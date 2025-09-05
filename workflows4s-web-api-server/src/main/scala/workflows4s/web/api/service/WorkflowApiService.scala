package workflows4s.web.api.service

import cats.effect.IO
import workflows4s.web.api.model.*
import io.circe.Json

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def getProgress(definitionId: String, instanceId: String): IO[ProgressResponse]
  def createTestInstance(definitionId: String): IO[WorkflowInstance]
}

class MockWorkflowApiService extends WorkflowApiService {

  private val mockDefinitions = List(
    WorkflowDefinition("course-registration-v1", "Course Registration", Some("Student course registration workflow")),
    WorkflowDefinition("pull-request-v1", "Pull Request", Some("Code review workflow")),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "course-registration-v1", status = InstanceStatus.Running, state = Some(Json.fromString("browsing"))),
    WorkflowInstance("inst-2", "course-registration-v1", status = InstanceStatus.Completed, state = Some(Json.fromString("registered"))),
    WorkflowInstance("inst-3", "course-registration-v1", status = InstanceStatus.Failed, state = Some(Json.fromString("validation_failed"))),
    WorkflowInstance("inst-4", "course-registration-v1", status = InstanceStatus.Failed, state = Some(Json.fromString("processing"))),
    WorkflowInstance("inst-5", "pull-request-v1", status = InstanceStatus.Running, state = Some(Json.fromString("review"))),
    WorkflowInstance("inst-6", "pull-request-v1", status = InstanceStatus.Completed, state = None),
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

  def getProgress(definitionId: String, instanceId: String): IO[ProgressResponse] =
    for {
      _        <- IO.fromOption(mockDefinitions.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
      instance <- IO.fromOption(mockInstances.find(i => i.id == instanceId && i.definitionId == definitionId))(
                    new Exception(s"Instance not found: $instanceId"),
                  )
    } yield {

      val steps = List(
        ProgressStep(
          stepType = "Pure",
          meta = ProgressStepMeta(
            name = Some("Initialize"),
            signalName = None,
            operationName = None,
            error = None,
            description = None,
          ),
          result = Some(
            ProgressStepResult(
              status = "Completed",
              index = 0,
              state = Some("initialized"),
            ),
          ),
        ),
        ProgressStep(
          stepType = "HandleSignal",
          meta = ProgressStepMeta(
            name = Some("Start Browsing"),
            signalName = Some("startBrowsing"),
            operationName = None,
            error = None,
            description = None,
          ),
          result = instance.status match {
            case InstanceStatus.Running   => Some(ProgressStepResult("Completed", 1, Some("browsing")))
            case InstanceStatus.Completed => Some(ProgressStepResult("Completed", 1, Some("browsing")))
            case InstanceStatus.Failed    => Some(ProgressStepResult("Failed", 1, None))
            case InstanceStatus.Paused    => None
          },
        ),
        ProgressStep(
          stepType = "RunIO",
          meta = ProgressStepMeta(
            name = Some("Process Registration"),
            signalName = None,
            operationName = None,
            error = None,
            description = None,
          ),
          result = instance.status match {
            case InstanceStatus.Running   => None
            case InstanceStatus.Completed => Some(ProgressStepResult("Completed", 2, Some("registered")))
            case InstanceStatus.Failed    => Some(ProgressStepResult("Failed", 2, None))
            case InstanceStatus.Paused    => None
          },
        ),
      )

      ProgressResponse(
        progressType = "Sequence",
        isCompleted = instance.status == InstanceStatus.Completed || instance.status == InstanceStatus.Failed,
        steps = steps,
        mermaidUrl = "https://mermaid.live/edit#base64:eyJjb2RlIjoiZmxvd2NoYXJ0IFREXG5zdGFydChbU3RhcnRdKVxuZW5kKFtFbmRdKVxuc3RhcnQgLS0+IGVuZCIsIm1lcm1haWQiOnsidGhlbWUiOiJkZWZhdWx0In0sInVwZGF0ZUVkaXRvciI6ZmFsc2UsImF1dG9TeW5jIjp0cnVlLCJ1cGRhdGVEaWFncmFtIjpmYWxzZX0",
        mermaidCode = "",
      )
    }

  def createTestInstance(definitionId: String): IO[WorkflowInstance] =
    for {
      _ <- IO.fromOption(mockDefinitions.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))
    } yield {
      val instanceId = s"test-${System.currentTimeMillis()}"
      WorkflowInstance(instanceId, definitionId, InstanceStatus.Running, Some(Json.fromString("test_instance")))
    }
}
