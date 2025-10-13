package workflows4s.ui.bundle

import sttp.tapir.*
import sttp.tapir.files.staticResourcesGetServerEndpoint
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.model.UIConfig

/** Defines Tapir endpoints for serving the Web UI bundle.
  */
object UiEndpoints {

  def get[F[_]](config: UIConfig): List[ServerEndpoint[Any, F]] = List(
    uiConfigEndpoint(config),
    uiBundleEndpoint,
  )

  private def uiConfigEndpoint[F[_]](config: UIConfig) = endpoint.get
    .in("ui" / "config.json")
    .out(jsonBody[UIConfig])
    .serverLogicSuccessPure[F](_ => config)

  private def uiBundleEndpoint[F[_]] = staticResourcesGetServerEndpoint[F]("ui")(
    this.getClass.getClassLoader,
    "workflows4s-web-ui-bundle",
  )
}
