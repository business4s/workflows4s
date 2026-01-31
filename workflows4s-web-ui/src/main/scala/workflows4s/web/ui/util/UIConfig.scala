package workflows4s.web.ui.util

import cats.effect.{Deferred, IO}
import org.scalajs.dom
import sttp.client4.UriContext
import workflows4s.web.api.model.UIConfig

object UIConfig {

  def detectBasePathFromLocation(pathname: String): String =
    if pathname == "/ui" || pathname.startsWith("/ui/") then "/ui" else ""

  private def configUrlFromLocation: String = {
    val origin = dom.window.location.origin
    val base   = detectBasePathFromLocation(dom.window.location.pathname)
    s"$origin$base/config.json"
  }

  private val _config: Deferred[IO, UIConfig] = Deferred.unsafe

  def get: IO[UIConfig] = _config.get

  def load: IO[Unit] = {
    (for {
      response <- IOFromPromise(dom.fetch(configUrlFromLocation))
      text     <- IOFromPromise(response.text())
      cfg      <- IO.fromEither(io.circe.parser.decode[UIConfig](text))
      _        <- _config.complete(cfg)
      _         = println(s"UI config set to ${cfg}")
    } yield ())
      .handleErrorWith(ex => {
        println(s"Failed to fetch ui config. ${ex}")
        _config.complete(new UIConfig(uri"http://localhost:8081", false)).void
      })
  }

}
