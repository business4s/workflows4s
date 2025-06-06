package workflows4s.web.api.service

import workflows4s.web.api.model.*
import scala.concurrent.Future
import java.time.Instant

trait WorkflowApiService {
  def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]]
  def getDefinition(id: String): Future[Either[String, WorkflowDefinition]]
  def getInstance(definitionId: String, instanceId: String): Future[Either[String, WorkflowInstance]]   
}

class MockWorkflowApiService extends WorkflowApiService {
  
  private val mockDefinitions = List(
    WorkflowDefinition(
      id = "withdrawal-v1",
      name = "Withdrawal Workflow", 
      description = Some("Handles bank withdrawals"),
      version = "1.0.0",
      createdAt = Instant.now()
    ),
    WorkflowDefinition(
      id = "approval-v1",
      name = "Approval Workflow",
      description = Some("Document approval process"), 
      version = "1.0.0",
      createdAt = Instant.now()
    )
  )

  // Mock instances data (keeping existing data)
  private val mockInstances = List(
    WorkflowInstance("inst-1", "withdrawal-v1", InstanceStatus.Running, Instant.now().minusSeconds(3600), Instant.now(), Some("validation")),
    WorkflowInstance("inst-2", "withdrawal-v1", InstanceStatus.Completed, Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), None),
    WorkflowInstance("inst-3", "withdrawal-v1", InstanceStatus.Failed, Instant.now().minusSeconds(10800), Instant.now().minusSeconds(9000), Some("approval")),
    WorkflowInstance("inst-4", "withdrawal-v1", InstanceStatus.Running, Instant.now().minusSeconds(1800), Instant.now(), Some("processing")),
    WorkflowInstance("inst-5", "approval-v1", InstanceStatus.Running, Instant.now().minusSeconds(900), Instant.now(), Some("review")),
    WorkflowInstance("inst-6", "approval-v1", InstanceStatus.Completed, Instant.now().minusSeconds(14400), Instant.now().minusSeconds(14000), None),
  )

  def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]] =
    Future.successful(Right(mockDefinitions))

  def getDefinition(id: String): Future[Either[String, WorkflowDefinition]] =
    Future.successful(
      mockDefinitions.find(_.id == id)
        .toRight(s"Definition not found: $id")
    )

  def listInstances(definitionId: String, filter: InstancesFilter): Future[Either[String, PaginatedResponse[WorkflowInstance]]] = {
    // Filter by definition ID
    val forDefinition = mockInstances.filter(_.definitionId == definitionId)
    
    if (forDefinition.isEmpty) {
      return Future.successful(Left(s"Definition not found: $definitionId"))
    }

    // Apply filters
    val filtered = forDefinition
      .filter(inst => filter.status.forall(_ == inst.status))
      .filter(inst => filter.createdAfter.forall(inst.createdAt.isAfter))
      .filter(inst => filter.createdBefore.forall(inst.createdAt.isBefore))
    
    // Apply pagination
    val offset = filter.offset.getOrElse(0)
    val limit = filter.limit.getOrElse(10)
    val page = filtered.drop(offset).take(limit)

    Future.successful(Right(PaginatedResponse(
      items = page,
      total = filtered.length,
      hasMore = filtered.length > offset + limit
    )))
  }
 
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