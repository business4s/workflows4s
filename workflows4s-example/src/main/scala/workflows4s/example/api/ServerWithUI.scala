package workflows4s.example.api

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.typesafe.scalalogging.StrictLogging
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.ui.bundle.UiEndpoints
import workflows4s.web.api.model.UIConfig

object ServerWithUI extends IOApp.Simple with BaseServer with StrictLogging {

  val port = 8080

  def run: IO[Unit] = {
    for {
      // Get API routes from BaseServer
      api <- apiRoutes
      // Convert Tapir endpoints to Http4s routes

      apiUrl = sys.env.getOrElse("WORKFLOWS4S_API_URL", s"http://localhost:${port}")

      uiRoutes  = Http4sServerInterpreter[IO]().toRoutes(UiEndpoints.get(UIConfig(sttp.model.Uri.unsafeParse(apiUrl), true)))
      // Add redirect from /ui to /ui/
      redirect  = org.http4s.HttpRoutes.of[IO] { case req @ org.http4s.Method.GET -> Root / "ui" =>
                    org.http4s
                      .Response[IO](org.http4s.Status.PermanentRedirect)
                      .putHeaders(org.http4s.headers.Location(req.uri / ""))
                      .pure[IO]
                  }
      // Combine with UI routes from UiEndpoints
      allRoutes = api <+> redirect <+> uiRoutes
      _        <- EmberServerBuilder
                    .default[IO]
                    .withHost(ipv4"0.0.0.0")
                    .withPort(Port.fromInt(8080).get)
                    .withHttpApp(allRoutes.orNotFound)
                    .build
                    .use { server =>
                      IO(logger.info(s"Server with UI running at http://${server.address}")) *>
                        IO.never
                    }
    } yield ()
  }
}
