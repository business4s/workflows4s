package workflows4s.ui.bundle

import cats.effect.IO
import org.http4s.{HttpRoutes, StaticFile}
import org.http4s.dsl.io._


object UiEndpoints {

  /**
   * HTTP routes that serve the bundled UI files using HTTP4s StaticFile
   * This follows the same pattern used elsewhere in the codebase
   */
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    
    // Serve static files from classpath resources
    case request @ GET -> path if path.segments.headOption.contains("ui") =>
      val resourcePath = path.segments.mkString("/")
      StaticFile.fromResource(resourcePath, Some(request))
        .getOrElseF(NotFound())
    
    // SPA fallback - serve index.html for any route that doesn't start with /api
    case request @ GET -> path if !path.segments.headOption.contains("api") =>
      StaticFile.fromResource("ui/index.html", Some(request))
        .getOrElseF(NotFound())
  }
}