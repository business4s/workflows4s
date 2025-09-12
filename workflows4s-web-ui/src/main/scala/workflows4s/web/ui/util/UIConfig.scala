package workflows4s.web.ui.util

import cats.effect.IO
import org.scalajs.dom
import sttp.client4.UriContext
import workflows4s.web.api.model.UIConfig

object UIConfig {

  private var _config: UIConfig = new UIConfig(uri"http://localhost:8081", false)

  def get: UIConfig = _config

  def set(value: UIConfig): Unit = _config = value

  def reload: IO[Unit] = {
    (for {
      // this will not work when ui if served under /ui instead of /ui/
      // user has to ensure redirect on their side because tapir is not flexible enough to handle it
      response <- IOFromPromise(dom.fetch("config.json"))
      text     <- IOFromPromise(response.text())
      cfg      <- IO.fromEither(io.circe.parser.decode[UIConfig](text))
      _        <- IO { _config = cfg }
    } yield ())
      .handleError(ex => println(s"Failed to fetch ui config. ${ex}"))
  }

}
