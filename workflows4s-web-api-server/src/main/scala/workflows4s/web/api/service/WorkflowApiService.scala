package workflows4s.web.api.service

import workflows4s.web.api.model.*
import cats.effect.IO
import io.circe.Json

trait WorkflowApiService {
  def listDefinitions(): IO[List[WorkflowDefinition]]
  def getDefinition(id: String): IO[WorkflowDefinition]
  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance]
  def getProgress(definitionId: String, instanceId: String): IO[ProgressResponse]
  def getProgressAsMermaid(definitionId: String, instanceId: String): IO[String]
}

class MockWorkflowApiService extends WorkflowApiService {

  private val mockDefinitions = List(
    WorkflowDefinition("course-registration-v1", "Course Registration"),
    WorkflowDefinition("pull-request-v1", "Pull Request"),
  )

  private val mockInstances = List(
    WorkflowInstance("inst-1", "course-registration-v1", status = InstanceStatus.Running, state = Some(Json.fromString("validation"))),
    WorkflowInstance("inst-2", "course-registration-v1", status = InstanceStatus.Completed, state = None),
    WorkflowInstance("inst-3", "course-registration-v1", status = InstanceStatus.Running, state = Some(Json.fromString("approval"))),
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
      inst        <- getInstance(definitionId, instanceId)
      // Create a simplified progress JSON structure
      progressJson = Json.obj(
                       "_type" -> Json.fromString("Sequence"),
                       "steps" -> Json.arr(
                         Json.obj(
                           "_type"  -> Json.fromString("Pure"),
                           "meta"   -> Json.obj("name" -> Json.fromString("Initialize")),
                           "result" -> Json.obj(
                             "_status" -> Json.fromString("Completed"),
                             "index"   -> Json.fromInt(0),
                             "state"   -> Json.fromString("initialized"),
                           ),
                         ),
                         Json.obj(
                           "_type"  -> Json.fromString("RunIO"),
                           "meta"   -> Json.obj("name" -> Json.fromString("Processing")),
                           "result" -> (inst.status match {
                             case InstanceStatus.Running   => Json.Null
                             case InstanceStatus.Completed =>
                               Json.obj(
                                 "_status" -> Json.fromString("Completed"),
                                 "index"   -> Json.fromInt(1),
                                 "state"   -> Json.fromString("completed"),
                               )
                             case InstanceStatus.Failed    =>
                               Json.obj(
                                 "_status" -> Json.fromString("Failed"),
                                 "index"   -> Json.fromInt(1),
                                 "state"   -> Json.Null,
                               )
                             case InstanceStatus.Paused    => Json.Null
                           }),
                         ),
                       ),
                     )
    } yield ProgressResponse(progressJson)

  def getProgressAsMermaid(definitionId: String, instanceId: String): IO[String] =
    for {
      inst <- getInstance(definitionId, instanceId)
    } yield s"""
    flowchart TD
      A[Initialize] --> B[Processing]
      B --> C[Complete]
      
      classDef completed fill:#e8f5e8
      classDef running fill:#e1f5fe
      classDef failed fill:#ffebee
      classDef pending fill:#f5f5f5
      
      class A completed
      class B ${inst.status match {
        case InstanceStatus.Running   => "running"
        case InstanceStatus.Completed => "completed"
        case InstanceStatus.Failed    => "failed"
        case InstanceStatus.Paused    => "pending"
      }}
      class C ${if inst.status == InstanceStatus.Completed then "completed" else "pending"}
    """.trim
}
