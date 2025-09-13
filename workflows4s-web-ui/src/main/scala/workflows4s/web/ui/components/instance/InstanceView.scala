package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowInstance
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.components.instance.InstanceView.Msg
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

  def sectionHeader(text: String) = h4(cls := "mt-4")(text)

  override def view: Html[InstanceView.Msg] =
    div(cls := "content mt-4")(
      h3(s"Instance: ${instance.id}"),
      ReusableViews.instanceField("Template", Html.span(instance.templateId)),
      sectionHeader("Signals"),
      signalsView.view.map(Msg.ForSignalsView(_)),
      sectionHeader("State"),
      instanceStateView,
      sectionHeader("Progress"),
      progressVisualizationView
    )

  private def instanceStateView: Html[InstanceView.Msg] = {
    div(
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

  private def progressVisualizationView: Html[InstanceView.Msg] =
    div(
      diagramView.view.map(InstanceView.Msg.ForDiagram(_)),
      div(cls := "field is-grouped mb-4")(
        div(cls := "control")(
          a(
            cls    := "button is-success is-small",
            href   := instance.mermaidUrl,
            target := "_blank",
          )("🔗 View in Mermaid Live"),
        ),
      ),
    )

}

object InstanceView {

  def initial(instance: WorkflowInstance) = {
    InstanceView(instance, MermaidDiagramView(instance.mermaidCode), SignalsView(instance, None)) -> Cmd.emit(
      Msg.ForDiagram(MermaidDiagramView.Msg.Retry),
    )
  }

  enum Msg {
    case ForDiagram(msg: MermaidDiagramView.Msg)
    case ForSignalsView(msg: SignalsView.Msg)
  }

}
