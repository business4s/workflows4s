package workflows4s.web.ui.components.instance

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.scalajs.dom
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.instance.MermaidDiagramView.Msg
import workflows4s.web.ui.util.{IOFromPromise, MermaidJS, MermaidSupport}

import scala.concurrent.duration.DurationInt
import scala.scalajs.js

case class MermaidDiagramView(code: String, svg: Option[String]) {

  def update(msg: MermaidDiagramView.Msg): (MermaidDiagramView, Cmd[IO, MermaidDiagramView.Msg]) = msg match {
    case Msg.SvgReady(svg) => (this.copy(svg = svg.some), Cmd.None)
    case Msg.Render        =>
      val renderCmd =
        if MermaidSupport.mermaidAvailable then Cmd.Run(renderMermaidDiagram)
        else Cmd.Run(IO.sleep(500.millis).as(Msg.Render))
      (this, renderCmd)
  }

  def view: Html[MermaidDiagramView.Msg] = {
    div()(
      svg match {
        case Some(svg) =>
          div(cls := "box")(
            Html.raw(MermaidWebComponent.name)(svg),
          )
        case None      =>
          div(cls := "notification is-info is-light has-text-centered")(
            text("🔄 Rendering diagram..."),
          )
      },
    )
  }

  private def renderMermaidDiagram: IO[MermaidDiagramView.Msg] = {
    for {
      renderResult <- IOFromPromise(MermaidJS.render("mermaid-diagram", code))
    } yield Msg.SvgReady(renderResult.svg)
  }

}

object MermaidDiagramView {

  def initial(code: String): (MermaidDiagramView, Cmd[IO, Msg]) = {
    (MermaidDiagramView(code, None), Cmd.Emit(Msg.Render))
  }

  enum Msg {
    case Render
    case SvgReady(svg: String)
  }

}

// we have to opt-out from tyrian rendering pipeline, otherwise html labels will not render
// there is some issue with svg+foreignObjects+tyrian
object MermaidWebComponent {

  val name = "svg-container"

  def register(): IO[Unit] = IO {
    if js.isUndefined(dom.window.customElements.asInstanceOf[js.Dynamic].get(name)) then {
      class MermaidDiagramElement extends dom.HTMLElement {
        def connectedCallback(): Unit = {
          this.innerHTML = this.innerHTML
        }
      }

      dom.window.customElements.define(name, js.constructorOf[MermaidDiagramElement])
    }
  }
}
