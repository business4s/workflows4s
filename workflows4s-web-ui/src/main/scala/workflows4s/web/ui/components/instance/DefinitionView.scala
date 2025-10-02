package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.util.Component
import workflows4s.web.ui.components.instance.DefinitionView.Msg

case class DefinitionView(definition: WorkflowDefinition, diagramView: MermaidDiagramView) extends Component {

  override type Self = DefinitionView
  override type Msg  = DefinitionView.Msg

  override def update(msg: Msg): (DefinitionView, Cmd[IO, Msg]) = msg match {
    case Msg.ForDiagram(msg) =>
      val (newDiagram, cmd) = diagramView.update(msg)
      this.copy(diagramView = newDiagram) -> cmd.map(Msg.ForDiagram(_))
  }

  override def view: Html[Msg] =
    div(cls := "content mt-4")(
      h3(s"Definition: ${definition.name}"),
      div(cls := "field is-grouped mb-4")(
        div(cls := "control")(
          a(
            cls    := "button is-success is-small",
            href   := definition.mermaidUrl,
            target := "_blank",
          )("🔗 View in Mermaid Live"),
        ),
      ),
      diagramView.view.map(Msg.ForDiagram(_)),
    )
}

object DefinitionView {
  def initial(defn: WorkflowDefinition) = {
    val (mermaidView, cmd) = MermaidDiagramView.initial(defn.mermaidCode)
    DefinitionView(defn, mermaidView) -> cmd.map(Msg.ForDiagram(_))
  }

  enum Msg {
    case ForDiagram(msg: MermaidDiagramView.Msg)
  }
}
