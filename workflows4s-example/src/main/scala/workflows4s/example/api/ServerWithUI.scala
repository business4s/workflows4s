package workflows4s.example.api

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import workflows4s.ui.bundle.UiEndpoints

object ServerWithUI extends IOApp.Simple with BaseServer {

  def run: IO[Unit] = {
    for {
      // Get API routes from BaseServer
      api <- apiRoutes
      // Combine with UI routes from UiEndpoints
      allRoutes = api <+> UiEndpoints.routes
      _        <- EmberServerBuilder
                    .default[IO]
                    .withHost(ipv4"0.0.0.0")
                    .withPort(port"8080")
                    .withHttpApp(allRoutes.orNotFound)
                    .build
                    .use { server =>
                      IO.println(s"Server with UI running at http://${server.address}") *>
                        IO.never
                    }
    } yield ()
  }
}