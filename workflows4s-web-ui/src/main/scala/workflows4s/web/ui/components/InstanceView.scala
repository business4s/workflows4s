package workflows4s.web.ui.components

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowInstance
import workflows4s.web.ui.components.InstanceView.Msg
import workflows4s.web.ui.components.util.Component

case class InstanceView(instance: WorkflowInstance, diagramView: MermaidDiagramView, signalsView: SignalsView) extends Component {

  override type Self = InstanceView
  override type Msg  = InstanceView.Msg

  override def update(msg: InstanceView.Msg): (InstanceView, Cmd[IO, InstanceView.Msg]) = msg match {
    case Msg.ForDiagram(msg)     =>
      val (newDiagramView, cmd) = diagramView.update(msg)
      this.copy(diagramView = newDiagramView) -> cmd.map(Msg.ForDiagram(_))
    case Msg.ForSignalsView(msg) =>
      val (newCmp, cmd) = signalsView.update(msg)
      this.copy(signalsView = newCmp) -> cmd.map(Msg.ForSignalsView(_))
  }

  override def view: Html[InstanceView.Msg] =
    div(
      instanceDetailsView,
      progressVisualizationView,
    )

  private def instanceDetailsView: Html[InstanceView.Msg] =
    div(cls := "content mt-4")(
      h3(s"Instance: ${instance.id}"),
      ReusableViews.instanceField("Definition", Html.span(instance.templateId)),
      signalsView.view.map(Msg.ForSignalsView(_)),
      instanceStateView,
    )

  private def instanceStateView: Html[InstanceView.Msg] =
    jsonStateViewer

  private def progressVisualizationView: Html[InstanceView.Msg] =
    div(cls := "mt-5")(
      h4("Workflow Progress"),
      mermaidDiagramView,
    )

  private def mermaidDiagramView: Html[InstanceView.Msg] =
    div(cls := "box mt-4")(
      h5("🎨 Workflow Diagram"),
      // Action buttons
      div(cls := "field is-grouped mb-4")(
        div(cls := "control")(
          a(
            cls    := "button is-success is-small",
            href   := instance.mermaidUrl,
            target := "_blank",
          )("🔗 View in Mermaid Live"),
        ),
      ),
      diagramView.view.map(InstanceView.Msg.ForDiagram(_)),
    )

  // 2. Add the missing jsonStateViewer method
  private def jsonStateViewer: Html[Nothing] = {
    div(cls := "box mt-4")(
      h5("Instance State"),
      instance.state match {
        case Some(stateJson) =>
          pre(cls := "mt-2 content is-small")(
            code(stateJson.spaces2),
          )
        case None            =>
          div(cls := "notification is-light")(
            text("No state data available"),
          )
      },
    )
  }
}

object InstanceView {

  def initial(instance: WorkflowInstance) = {
    InstanceView(instance, MermaidDiagramView(instance.mermaidCode, None), SignalsView(instance, None)) -> Cmd.emit(
      Msg.ForDiagram(MermaidDiagramView.Msg.Retry),
    )
  }

  enum Msg {
    case ForDiagram(msg: MermaidDiagramView.Msg)
    case ForSignalsView(msg: SignalsView.Msg)
  }

}
