package workflows4s.web.ui.util

import cats.effect.{Deferred, IO}
import org.scalajs.dom
import sttp.client4.UriContext
import workflows4s.web.api.model.UIConfig

object UIConfig {

  private val _config: Deferred[IO, UIConfig] = Deferred.unsafe

  def get: IO[UIConfig] = _config.get

  def load: IO[Unit] = {
    (for {
      // this will not work when ui if served under /ui instead of /ui/
      // user has to ensure redirect on their side because tapir is not flexible enough to handle it
      response <- IOFromPromise(dom.fetch("config.json"))
      text     <- IOFromPromise(response.text())
      cfg      <- IO.fromEither(io.circe.parser.decode[UIConfig](text))
      _        <- _config.complete(cfg)
      _         = println(s"UI config set to ${_config} from ${cfg}")
    } yield ())
      .handleErrorWith(ex => {
        println(s"Failed to fetch ui config. ${ex}")
        _config.complete(new UIConfig(uri"http://localhost:8081", false)).void
      })
  }

}
