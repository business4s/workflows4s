package workflows4s.web.ui.components.instance

import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeId
import org.scalajs.dom
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.instance.MermaidDiagramView.Msg
import workflows4s.web.ui.util.{MermaidHelper, MermaidJS}

import scala.concurrent.duration.DurationInt
import scala.scalajs.js

case class MermaidDiagramView(code: String) {

  def update(msg: MermaidDiagramView.Msg): (MermaidDiagramView, Cmd[IO, MermaidDiagramView.Msg]) = msg match {
    case Msg.NoOp  => (this, Cmd.None)
    case Msg.Retry =>
      val renderCmd =
        if MermaidHelper.mermaidAvailable then Cmd.Run(renderMermaidDiagram)
        else Cmd.Run(IO.sleep(500.millis).as(Msg.Retry))
      (this, renderCmd)

  }

  def view: Html[MermaidDiagramView.Msg] =
    div(id := "my-mermaid-container")(
      div(cls := "notification is-info is-light has-text-centered")(
        text("🔄 Rendering diagram..."),
      ),
    )

  private def renderMermaidDiagram: IO[MermaidDiagramView.Msg] = {
    if MermaidHelper.mermaidAvailable then {
      for {
        // todo, initialize shouldn't happen every time
        _            <- IO(MermaidJS.initialize(js.Dynamic.literal("startOnLoad" -> false, "htmlLabels" -> true)))
        renderResult <- MermaidHelper.fromPromise(MermaidJS.render("mermaid-diagram", code))
        // we have to opt-out from tyrian rendering pipeline, otherwise html labels will not render
        // there is some issue wbetter svg+foreignObjects+tyrian
        _             = dom.document.getElementById("my-mermaid-container").innerHTML = renderResult.svg
      } yield Msg.NoOp
    } else {
      println("Mermaid isn't ready, retrying...")
      Msg.Retry.pure[IO]
    }
  }

}

object MermaidDiagramView {

  enum Msg {
    case Retry
    case NoOp
  }

}
