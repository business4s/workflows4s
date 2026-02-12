package workflows4s.example.api

import cats.effect.{IO, IOApp}
import com.typesafe.scalalogging.StrictLogging

object ServerWithUI extends IOApp.Simple with BaseServer with StrictLogging {

  val port = 8080

  def run: IO[Unit] = {
    val apiUrl = sys.env.getOrElse("WORKFLOWS4S_API_URL", s"http://localhost:${port}")
    serverWithUi(port, apiUrl).use { server =>
      IO(logger.info(s"Server with UI running at http://${server.address}")) *>
        IO.never
    }
  }
}
