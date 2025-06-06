package workflows4s.web.api.server

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.Directives.*
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.service.WorkflowApiService
import workflows4s.web.api.model.*
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WorkflowApiServer(service: WorkflowApiService)(using actorSystem: ActorSystem[?]) {
  
  given ExecutionContext = actorSystem.executionContext

  private val interpreter = PekkoHttpServerInterpreter()

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

  // First endpoint: List all definitions
  private val definitionsRoute = interpreter.toRoute(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => service.listDefinitions())
  )

  // Second endpoint: Get definition by ID
  private val getDefinitionRoute = interpreter.toRoute(
    WorkflowEndpoints.getDefinition.serverLogic(id => service.getDefinition(id))
  )

  // Third endpoint: List instances with filtering
  private val instancesRoute = interpreter.toRoute(
    WorkflowEndpoints.listInstances.serverLogic { 
      case (id, status, createdAfter, createdBefore, limit, offset) => 
        parseInstancesQuery(id, status, createdAfter, createdBefore, limit, offset)
    }
  )

  // Fourth endpoint: Get specific instance
  private val getInstanceRoute = interpreter.toRoute(
    WorkflowEndpoints.getInstance.serverLogic { 
      case (defId, instanceId) => service.getInstance(defId, instanceId) 
    }
  )

 
  private val swaggerEndpoints = SwaggerInterpreter()
    .fromEndpoints[Future](WorkflowEndpoints.allEndpoints, "Workflows4s API", "1.0.0")
  private val swaggerRoute = interpreter.toRoute(swaggerEndpoints)

 
  private val routes: Route = definitionsRoute ~ getDefinitionRoute ~ instancesRoute ~ getInstanceRoute ~ swaggerRoute

  def start(host: String = "localhost", port: Int = 8081): Future[Http.ServerBinding] = {
    Http().newServerAt(host, port).bind(routes).andThen {
      case Success(binding) => 
        println(s" Workflows4s API server started at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}")
        println(s" API docs available at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/docs")
        println(s" Test all 4 endpoints:")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1")
        println(s"   curl \"http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1/instances?status=Running&limit=5\"")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1/instances/inst-1")
      case Failure(ex) => 
        println(s" Failed to start API server: ${ex.getMessage}")
    }
  }
}