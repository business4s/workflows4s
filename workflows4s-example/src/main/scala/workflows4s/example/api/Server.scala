package workflows4s.example.api

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import com.typesafe.scalalogging.StrictLogging
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

object Server extends IOApp.Simple, BaseServer, StrictLogging {

  def run: IO[Unit] = {
    apiRoutes
      .flatMap(routes =>
        EmberServerBuilder
          .default[IO]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8081")
          .withHttpApp(routes.orNotFound)
          .build,
      )
      .use { server =>
        IO(logger.info(s"API Server running at http://${server.address}")) *>
          IO.never
      }
  }
}
