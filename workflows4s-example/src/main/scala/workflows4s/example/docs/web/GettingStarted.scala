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
import workflows4s.web.api.server.{SignalSupport, WorkflowEntry, WorkflowServerEndpoints}
import workflows4s.wio.WCState

object GettingStarted {

  object ServerWithUI extends IOApp.Simple {
    def run: IO[Unit] = {

      // doc_start
      // the same runtime that is used for running the workflows
      val myRuntime: WorkflowRuntime[IO, MyWorkflowCtx] = ???

      // each runtime needs to be enriched with information required by the UI
      val myApiEntry: WorkflowEntry[IO, MyWorkflowCtx] =
        WorkflowEntry(
          name = "My Workflow",
          description = None,
          runtime = myRuntime,
          stateEncoder = ??? : Encoder[WCState[MyWorkflowCtx]],
          signalSupport = SignalSupport.NoSupport,
        )

      // tapir endpoints for serving the API, we dont configure search for now
      val apiEndpoints = WorkflowServerEndpoints.get[IO](List(myApiEntry), search = None)
      // tapir endpoints for service the UI assets
      val uiEndpoints  = UiEndpoints.get[IO](UIConfig(Uri.unsafeParse("http://localhost:8080")))

      // example tapir interpretation, this part is orthogonal to workflows4s
      val routes = Http4sServerInterpreter[IO]().toRoutes(apiEndpoints ++ uiEndpoints)
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
      // doc_end
    }
  }

}
