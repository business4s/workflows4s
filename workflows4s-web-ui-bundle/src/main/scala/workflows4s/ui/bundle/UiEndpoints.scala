package workflows4s.ui.bundle

import sttp.tapir.server.ServerEndpoint
import sttp.tapir.files.staticResourcesGetServerEndpoint
import sttp.tapir.stringToPath

/** Defines server-agnostic Tapir endpoints for serving the Web UI bundle.
  *
  * These endpoints can be interpreted by any server backend supported by Tapir
  *
  * The UI files are expected to be located in the "META-INF/resources/workflows4s/ui" resource directory of the JAR.
  */
object UiEndpoints {

  /** ServerEndpoints to serve the static UI content from the classpath.
    */
  def endpoints[F[_]]: List[ServerEndpoint[Any, F]] = List(
    staticResourcesGetServerEndpoint[F]("ui")(
      this.getClass.getClassLoader,
      "workflows4s-web-ui-bundle",
    ),
  )
}
