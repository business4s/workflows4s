 package workflows4s.web.api.server

import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.service.WorkflowApiService
import workflows4s.web.api.model.*
import java.time.Instant
import scala.concurrent.Future

class WorkflowServerEndpoints(service: WorkflowApiService) {
  
  // Helper function to parse query parameters
  private def parseInstancesQuery(id: String, status: Option[String], createdAfter: Option[Long], 
                                 createdBefore: Option[Long], limit: Option[Int], offset: Option[Int]) = {
    val parsedStatus = status.flatMap(s => InstanceStatus.values.find(_.toString.equalsIgnoreCase(s)))
    val filter = InstancesFilter(
      status = parsedStatus,
      createdAfter = createdAfter.map(Instant.ofEpochMilli),
      createdBefore = createdBefore.map(Instant.ofEpochMilli),
      limit = limit,
      offset = offset
    )
    service.listInstances(id, filter)
  }

  def endpoints: List[ServerEndpoint[Any, Future]] = List(
    // First endpoint: List all definitions
    WorkflowEndpoints.listDefinitions.serverLogic(_ => service.listDefinitions()),
    
    // Second endpoint: Get definition by ID
    WorkflowEndpoints.getDefinition.serverLogic(id => service.getDefinition(id)),
    
    // Third endpoint: List instances with filtering
    WorkflowEndpoints.listInstances.serverLogic { 
      case (id, status, createdAfter, createdBefore, limit, offset) => 
        parseInstancesQuery(id, status, createdAfter, createdBefore, limit, offset)
    },
    
    // Fourth endpoint: Get specific instance
    WorkflowEndpoints.getInstance.serverLogic { 
      case (defId, instanceId) => service.getInstance(defId, instanceId) 
    }
  )
}