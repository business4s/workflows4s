package workflows4s.web.api

import cats.effect.{IO, IOApp}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import workflows4s.web.api.server.WorkflowApiServer
import workflows4s.web.api.service.MockWorkflowApiService

object Main extends IOApp.Simple {
  
  def run: IO[Unit] = {
    given ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "workflows4s-api-system")
    
    for {
      _ <- IO.println("🚀 Starting Workflows4s API Server...")
      
      apiService = new MockWorkflowApiService()
      apiServer = new WorkflowApiServer(apiService)
      
      binding <- IO.fromFuture(IO(apiServer.start("localhost", 8081)))
      
      _ <- IO.println("✅ API Server started!")
      _ <- IO.never
      
    } yield ()
  }
}