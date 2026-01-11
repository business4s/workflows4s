package workflows4s.web.ui.components.util

import cats.effect.IO
import io.circe.Json
import org.scalajs.dom
import tyrian.Html.*
import tyrian.*

import scala.scalajs.js

enum JsonViewMode {
  case Tree, Raw
}

sealed trait JsonViewMsg
object JsonViewMsg {
  case class SetViewType(vt: JsonViewMode) extends JsonViewMsg
  case object Render extends JsonViewMsg
  case object NoOp extends JsonViewMsg
}

final case class JsonView(
    json: Json,
    viewId: String,
    viewType: JsonViewMode = JsonViewMode.Tree,
) extends Component {

  override type Self = JsonView
  override type Msg  = JsonViewMsg

  override def update(msg: JsonViewMsg): (JsonView, Cmd[IO, JsonViewMsg]) = msg match {
    case JsonViewMsg.SetViewType(vt) => this.copy(viewType = vt) -> Cmd.Run(IO.sleep(scala.concurrent.duration.DurationInt(50).millis).as(JsonViewMsg.Render))
    case JsonViewMsg.Render          =>
      val cmd = viewType match {
        case JsonViewMode.Tree => Cmd.Run(JsonView.renderTree(viewId, json).as(JsonViewMsg.NoOp))
        case JsonViewMode.Raw  => Cmd.Run(JsonView.renderRaw(viewId).as(JsonViewMsg.NoOp))
      }
      this -> cmd
    case JsonViewMsg.NoOp            => this -> Cmd.None
  }
  
  override def view: Html[JsonViewMsg] =
    div(cls := "json-view-container")(
      div(cls := "json-view-header")(
        div(cls := "json-view-toggle buttons has-addons")(
          button(
            cls := s"button is-small is-dark ${if viewType == JsonViewMode.Tree then "is-info is-selected" else ""}",
            onClick(JsonViewMsg.SetViewType(JsonViewMode.Tree)),
          )("Tree"),
          button(
            cls := s"button is-small is-dark ${if viewType == JsonViewMode.Raw then "is-info is-selected" else ""}",
            onClick(JsonViewMsg.SetViewType(JsonViewMode.Raw)),
          )("Raw"),
        ),
        button(
          cls := "button is-small is-dark copy-btn",
          onClick(JsonViewMsg.NoOp), // Handled by JS via class
          attribute("onclick", s"window.copyToClipboard(`${json.spaces2}` )"),
        )(
          span(cls := "icon is-small")(i(cls := "fas fa-copy")()),
          span()("Copy"),
        ),
      ),
      div(id := viewId, cls := "is-overflow-hidden")(
        if (viewType == JsonViewMode.Raw) {
          pre(cls := "m-0")(
            code(cls := "language-json", id := s"$viewId-raw")(json.spaces2),
          )
        } else {
          div(cls := "p-3", id := s"$viewId-tree")()
        },
      ),
    )
}

object JsonView {

  def renderTree(viewId: String, json: Json): IO[Unit] = IO {
    val container = dom.document.getElementById(s"$viewId-tree")
    if (container != null && js.typeOf(js.Dynamic.global.JSONFormatter) != "undefined") {
      val parsed    = js.JSON.parse(json.noSpaces)
      val isDark    = js.Dynamic.global.isDarkMode().asInstanceOf[Boolean]
      val theme     = if (isDark) "dark" else ""
      val formatter = js.Dynamic.newInstance(js.Dynamic.global.JSONFormatter)(parsed, 1, js.Dynamic.literal(theme = theme))
      val node      = formatter.render().asInstanceOf[dom.Node]
      container.innerHTML = ""
      val _ = container.appendChild(node)
    }
  }

  def renderRaw(viewId: String): IO[Unit] = IO {
    val element = dom.document.getElementById(s"$viewId-raw")
    if (element != null && js.typeOf(js.Dynamic.global.hljs) != "undefined") {
      val _ = js.Dynamic.global.hljs.highlightElement(element)
    }
  }

  def initial(viewId: String, json: Json): (JsonView, Cmd[IO, JsonViewMsg]) = {
    JsonView(json, viewId) -> Cmd.Run(IO.sleep(scala.concurrent.duration.DurationInt(50).millis).as(JsonViewMsg.Render))
  }
}
