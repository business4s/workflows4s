 package workflows4s.web.api.service

import workflows4s.web.api.model.*
import cats.effect.IO  
import io.circe.Json

trait WorkflowApiService {
  def listDefinitions(): IO[Either[String, List[WorkflowDefinition]]]  
  def getDefinition(id: String): IO[Either[String, WorkflowDefinition]] 
  def getInstance(definitionId: String, instanceId: String): IO[Either[String, WorkflowInstance]]  
}

class MockWorkflowApiService extends WorkflowApiService {
  
  private val mockDefinitions = List(
    WorkflowDefinition(
      id = "withdrawal-v1",
      name = "Withdrawal Workflow"
    ),
    WorkflowDefinition(
      id = "approval-v1",
      name = "Approval Workflow"
    )
  )

 
  private val mockInstances = List(
    WorkflowInstance("inst-1", "withdrawal-v1", Some(Json.fromString("validation"))),
    WorkflowInstance("inst-2", "withdrawal-v1", None),
    WorkflowInstance("inst-3", "withdrawal-v1", Some(Json.fromString("approval"))),
    WorkflowInstance("inst-4", "withdrawal-v1", Some(Json.fromString("processing"))),
    WorkflowInstance("inst-5", "approval-v1", Some(Json.fromString("review"))),
    WorkflowInstance("inst-6", "approval-v1", None)
  )

  def listDefinitions(): IO[Either[String, List[WorkflowDefinition]]] =
    IO.pure(Right(mockDefinitions)) 

  def getDefinition(id: String): IO[Either[String, WorkflowDefinition]] =
    IO.pure(  
      mockDefinitions.find(_.id == id)
        .toRight(s"Definition not found: $id")
    )
 
  def getInstance(definitionId: String, instanceId: String): IO[Either[String, WorkflowInstance]] = {
    // Check if definition exists
    if (!mockDefinitions.exists(_.id == definitionId)) {
      return IO.pure(Left(s"Definition not found: $definitionId")) 
    }

    // Find instance that belongs to the definition
    mockInstances.find(i => i.id == instanceId && i.definitionId == definitionId) match {
      case Some(instance) => IO.pure(Right(instance))  
      case None => IO.pure(Left(s"Instance not found: $instanceId"))
    }
  }
}