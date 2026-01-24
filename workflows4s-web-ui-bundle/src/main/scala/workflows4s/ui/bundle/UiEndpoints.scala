package workflows4s.ui.bundle

import sttp.tapir.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import workflows4s.web.api.model.UIConfig


object UiEndpoints {

  def get[F[_]](config: UIConfig): List[ServerEndpoint[Any, F]] = List(
    uiConfigEndpoint(config),
    uiResourcesEndpoint,
  )

  private def uiConfigEndpoint[F[_]](config: UIConfig) = endpoint.get
    .in("ui" / "config.json")
    .out(jsonBody[UIConfig])
    .serverLogicSuccessPure[F](_ => config)

  private def uiResourcesEndpoint[F[_]] = 
    endpoint.get
      .in("ui" / paths)
      .out(header[String]("Content-Type"))
      .out(inputStreamBody)
      .serverLogicPure[F] { pathList =>
        val sanitizedPathList = pathList.filter(_ != ".")
        val isValid = sanitizedPathList.forall { segment =>
          segment.nonEmpty && 
          segment != ".." && 
          !segment.contains('/') && 
          !segment.contains('\\') && 
          !segment.contains(':')
        }

        if (!isValid) {
          Left(())
        } else {
          val relativePath = sanitizedPathList.mkString("/")
          if (relativePath.isEmpty) {
            val indexStream = this.getClass.getClassLoader.getResourceAsStream("workflows4s-web-ui-bundle/index.html")
            if (indexStream != null) Right(("text/html", indexStream))
            else Left(())
          } else {
            val resourcePath = s"workflows4s-web-ui-bundle/$relativePath"
            // try to find the resource
            val stream = this.getClass.getClassLoader.getResourceAsStream(resourcePath)
            if (stream != null) {
              Right((guessContentType(relativePath), stream))
            } else {
              // fallback to index.html
              val indexStream = this.getClass.getClassLoader.getResourceAsStream("workflows4s-web-ui-bundle/index.html")
              if (indexStream != null) {
                Right(("text/html", indexStream))
              } else {
                Left(())
              }
            }
          }
        }
      }
  private def guessContentType(path: String): String = {
    val p = path.toLowerCase
    if (p.endsWith(".html")) "text/html"
    else if (p.endsWith(".js")) "application/javascript"
    else if (p.endsWith(".css")) "text/css"
    else if (p.endsWith(".json")) "application/json"
    else if (p.endsWith(".png")) "image/png"
    else if (p.endsWith(".svg")) "image/svg+xml"
    else if (p.endsWith(".jpg") || p.endsWith(".jpeg")) "image/jpeg"
    else if (p.endsWith(".gif")) "image/gif"
    else if (p.endsWith(".ico")) "image/x-icon"
    else if (p.endsWith(".woff2")) "font/woff2"
    else if (p.endsWith(".woff")) "font/woff"
    else if (p.endsWith(".ttf")) "font/ttf"
    else "application/octet-stream"
  }
}
