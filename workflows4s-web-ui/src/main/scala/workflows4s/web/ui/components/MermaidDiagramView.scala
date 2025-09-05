package workflows4s.web.ui.components

import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeId
import tyrian.{Cmd, Html}
import tyrian.Html.*
import workflows4s.web.ui.components.MermaidDiagramView.Msg
import workflows4s.web.ui.{MermaidHelper, MermaidJS}

import scala.concurrent.duration.DurationInt
import scala.scalajs.js

case class MermaidDiagramView(code: String, svg: Option[String]) {

  def update(msg: MermaidDiagramView.Msg): (MermaidDiagramView, Cmd[IO, MermaidDiagramView.Msg]) = msg match {

    case Msg.Retry =>
      val renderCmd =
        if MermaidHelper.mermaidAvailable then Cmd.Run(renderMermaidDiagram)
        else Cmd.Run(IO.sleep(500.millis).as(Msg.Retry))
      (this, renderCmd)

    case Msg.Rendered(svg) => this.copy(svg = Some(svg)) -> Cmd.None
  }

  def view: Html[MermaidDiagramView.Msg] =
    svg match {
      case Some(svg) =>
        div(cls := "has-text-centered p-4")().innerHtml(svg)
      case None      =>
        div(cls := "notification is-info is-light has-text-centered")(
          text("🔄 Rendering diagram..."),
        )
    }

  private def renderMermaidDiagram: IO[MermaidDiagramView.Msg] = {
    if MermaidHelper.mermaidAvailable then {
      val renderTask = for {
        _            <- IO(MermaidJS.initialize(js.Dynamic.literal("startOnLoad" -> false, "htmlLabels" -> false)))
        renderResult <- MermaidHelper.fromPromise(MermaidJS.render("mermaid-diagram", code))
      } yield {
        Msg.Rendered(renderResult.svg)
      }
      renderTask.handleError { ex =>
        Msg.Rendered(s"<div style='color: red; padding: 20px;'>Failed to render diagram: ${ex.getMessage}</div>")
      }
    } else {
      println("Mermaid isn't ready, retrying...")
      Msg.Retry.pure[IO]
    }
  }

}

object MermaidDiagramView {

  enum Msg {
    case Retry
    case Rendered(svg: String)
  }

}
