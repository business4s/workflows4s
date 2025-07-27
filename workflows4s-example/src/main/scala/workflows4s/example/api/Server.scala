package workflows4s.example.api

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

object Server extends IOApp.Simple with BaseServer {

  def run: IO[Unit] = {
    for {
      // Use shared API routes from BaseServer
      routes <- apiRoutes
      _      <- EmberServerBuilder
                  .default[IO]
                  .withHost(ipv4"0.0.0.0")
                  .withPort(port"8081")
                  .withHttpApp(routes.orNotFound)
                  .build
                  .use { server =>
                    IO.println(s"API Server running at http://${server.address}") *>
                      IO.never
                  }
    } yield ()
  }
}