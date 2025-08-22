//helper file, still need to decide where to put it exactly. For now, just put it in workflows4s-web-ui/src/main/scala/workflows4s/web/ui
package workflows4s.web.ui

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native
@JSGlobal("mermaid")
object MermaidJS extends js.Object {
  def initialize(config: js.Object): Unit = js.native
  def render(id: String, code: String): js.Promise[RenderResult] = js.native
}

@js.native
trait RenderResult extends js.Object {
  val svg: String = js.native
  val bindFunctions: js.UndefOr[js.Function0[Unit]] = js.native
}

object MermaidHelper {
  def fromPromise[A](p: js.Promise[A]): cats.effect.IO[A] =
    cats.effect.IO.fromFuture(cats.effect.IO(p.toFuture))

  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  def mermaidAvailable: Boolean =
    js.Dynamic.global.mermaid != null && js.Dynamic.global.mermaid != js.undefined
}