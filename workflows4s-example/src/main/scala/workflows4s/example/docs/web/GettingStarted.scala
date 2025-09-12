package workflows4s.example.docs.web

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{ipv4, port}
import io.circe.Encoder
import org.http4s.ember.server.EmberServerBuilder
import sttp.model.Uri
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.docs.wakeups.common.MyWorkflowCtx
import workflows4s.runtime.WorkflowRuntime
import workflows4s.ui.bundle.UiEndpoints
import workflows4s.web.api.model.UIConfig
import workflows4s.web.api.server.RealWorkflowService.SignalSupport
import workflows4s.web.api.server.{RealWorkflowService, WorkflowServerEndpoints}
import workflows4s.wio.WCState

object GettingStarted {

  object ServerWithUI extends IOApp.Simple {
    def run: IO[Unit] = {

      val myRuntime: WorkflowRuntime[IO, MyWorkflowCtx]                = ???
      val myApiEntry: RealWorkflowService.WorkflowEntry[IO, MyWorkflowCtx] = RealWorkflowService.WorkflowEntry(
        id = "my-workflow",
        name = "My Workflow",
        runtime = myRuntime,
        stateEncoder = ??? : Encoder[WCState[MyWorkflowCtx]],
        signalSupport = SignalSupport.NoSupport,
      )

      val apiEndpoints = WorkflowServerEndpoints.get(List(myApiEntry))
      val uiEndpoints  = UiEndpoints.get[IO](UIConfig(Uri.unsafeParse("http://localhost:8080")))
      val routes       = Http4sServerInterpreter[IO]().toRoutes(apiEndpoints ++ uiEndpoints)
      for {
        _ <- EmberServerBuilder
               .default[IO]
               .withHost(ipv4"0.0.0.0")
               .withPort(port"8080")
               .withHttpApp(routes.orNotFound)
               .build
               .use { server =>
                 IO.println(s"Server with UI running at http://${server.address}") *>
                   IO.never
               }
      } yield ()
    }
  }

}
