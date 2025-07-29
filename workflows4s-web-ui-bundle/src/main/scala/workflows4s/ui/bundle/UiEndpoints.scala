package workflows4s.ui.bundle

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.files.staticResourcesGetServerEndpoint
import sttp.tapir.stringToPath

/**
 * Defines server-agnostic Tapir endpoints for serving the Web UI bundle.
 *
 * These endpoints can be interpreted by any server backend supported by Tapir
 * 
 *
 * The UI files are expected to be located in the "META-INF/resources/workflows4s/ui"
 * resource directory of the JAR.
 */
object UiEndpoints {

  private val uiPathPrefix = "ui"
  private val resourcePath = "workflows4s/ui"

  /**
   * ServerEndpoints to serve the static UI content from the classpath.
   */
  val endpoints: List[ServerEndpoint[Any, IO]] = List(
    staticResourcesGetServerEndpoint[IO](uiPathPrefix)(
      this.getClass.getClassLoader,
      resourcePath
    )
  )
}
