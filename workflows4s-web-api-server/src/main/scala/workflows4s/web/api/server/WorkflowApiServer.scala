package workflows4s.web.api.server

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.Directives.*
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.service.WorkflowApiService
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WorkflowApiServer(service: WorkflowApiService)(using actorSystem: ActorSystem[?]) {
  
  // Create given ExecutionContext from ActorSystem
  given ExecutionContext = actorSystem.executionContext

 
  private val interpreter = PekkoHttpServerInterpreter()
  private val serverEndpoints = new WorkflowServerEndpoints(service)

  // Convert server endpoints to routes
  private val apiRoutes = interpreter.toRoute(serverEndpoints.endpoints)

  // Swagger documentation
  private val swaggerEndpoints = SwaggerInterpreter()
    .fromEndpoints[Future](WorkflowEndpoints.allEndpoints, "Workflows4s API", "1.0.0")
  private val swaggerRoute = interpreter.toRoute(swaggerEndpoints)

  // Combine all routes
  private val routes: Route = apiRoutes ~ swaggerRoute

  def start(host: String = "localhost", port: Int = 8081): Future[Http.ServerBinding] = {
    Http().newServerAt(host, port).bind(routes).andThen {
      case Success(binding) => 
        println(s" Workflows4s API server started at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}")
        println(s"API docs available at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/docs")
        println(s" Test all 4 endpoints:")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1")
        println(s"   curl \"http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1/instances?status=Running&limit=5\"")
        println(s"   curl http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}/api/v1/definitions/withdrawal-v1/instances/inst-1")
      case Failure(ex) => 
        println(s" Failed to start API server: ${ex.getMessage}")
    }(using actorSystem.executionContext) // Fix: Use 'using' clause for Scala 3.7
  }
}