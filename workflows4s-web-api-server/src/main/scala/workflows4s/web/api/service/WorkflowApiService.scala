 package workflows4s.web.api.service

import workflows4s.web.api.model.*
import scala.concurrent.Future

trait WorkflowApiService {
  def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]]
  def getDefinition(id: String): Future[Either[String, WorkflowDefinition]]
  def getInstance(definitionId: String, instanceId: String): Future[Either[String, WorkflowInstance]]   
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
    WorkflowInstance("inst-1", "withdrawal-v1", Some("validation"), None),
    WorkflowInstance("inst-2", "withdrawal-v1", None, None),
    WorkflowInstance("inst-3", "withdrawal-v1", Some("approval"), None),
    WorkflowInstance("inst-4", "withdrawal-v1", Some("processing"), None),
    WorkflowInstance("inst-5", "approval-v1", Some("review"), None),
    WorkflowInstance("inst-6", "approval-v1", None, None)
  )

  def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]] =
    Future.successful(Right(mockDefinitions))

  def getDefinition(id: String): Future[Either[String, WorkflowDefinition]] =
    Future.successful(
      mockDefinitions.find(_.id == id)
        .toRight(s"Definition not found: $id")
    )

  
 
  def getInstance(definitionId: String, instanceId: String): Future[Either[String, WorkflowInstance]] = {
    // Check if definition exists
    if (!mockDefinitions.exists(_.id == definitionId)) {
      return Future.successful(Left(s"Definition not found: $definitionId"))
    }

    // Find instance that belongs to the definition
    mockInstances.find(inst => inst.id == instanceId && inst.definitionId == definitionId) match {
      case Some(instance) => Future.successful(Right(instance))
      case None => Future.successful(Left(s"Instance not found: $instanceId for definition: $definitionId"))
    }
  }
}