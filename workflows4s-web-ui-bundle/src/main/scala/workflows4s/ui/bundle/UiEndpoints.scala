package workflows4s.ui.bundle

import sttp.tapir.*
import sttp.tapir.files.staticResourcesGetServerEndpoint
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.model.UIConfig

object UiEndpoints {

  // Reserved path segment for static assets - routes should never start with this
  // vite automatically puts all assets in this folder
  val AssetsPrefix = "assets"

  def get[F[_]](config: UIConfig): List[ServerEndpoint[Any, F]] = List(
    uiConfigEndpoint(config),
    uiAssetsEndpoint,
    uiSpaEndpoint,
  )

  private def uiConfigEndpoint[F[_]](config: UIConfig) = endpoint.get
    .in("ui" / "config.json")
    .out(jsonBody[UIConfig])
    .serverLogicSuccessPure[F](_ => config)

  // Serve static assets from /ui/assets/* using tapir's built-in static resources endpoint.
  // This properly returns 404 for missing assets instead of falling back to index.html.
  private def uiAssetsEndpoint[F[_]]: ServerEndpoint[Any, F] =
    staticResourcesGetServerEndpoint[F](s"ui" / AssetsPrefix)(
      this.getClass.getClassLoader,
      s"workflows4s-web-ui-bundle/$AssetsPrefix",
    )

  // SPA catch-all: any route under /ui/* that doesn't match assets returns index.html.
  // This enables client-side routing for the SPA.
  private def uiSpaEndpoint[F[_]]: ServerEndpoint[Any, F] =
    endpoint.get
      .in("ui" / paths)
      .out(header[String]("Content-Type"))
      .out(inputStreamBody)
      .serverLogicPure[F] { pathList =>
        // If someone requests /ui/assets/... and it got here, the asset doesn't exist
        // (the assets endpoint didn't match). Return an error so we get a 404.
        if pathList.headOption.contains(AssetsPrefix) then Left(())
        else Right(indexHtmlResponse)
      }

  private def indexHtmlResponse = {
    val indexStream = this.getClass.getClassLoader.getResourceAsStream("workflows4s-web-ui-bundle/index.html")
    assert(indexStream != null, "index.html not found in the classpath. Please check that workflows4s-web-ui-bundle is on the classpath.")
    ("text/html", indexStream)
  }
}
