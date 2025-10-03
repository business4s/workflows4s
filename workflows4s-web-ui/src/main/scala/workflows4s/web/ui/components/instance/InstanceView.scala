package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowInstance
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.util.{AsyncView, Component}

// Wrapper component that owns async loading and refresh
final case class InstanceView(
    workflowId: String,
    instanceId: String,
    content: AsyncView[InstanceView.Content, InstanceView.Content.Msg],
) extends Component {

  override type Self = InstanceView
  override type Msg  = InstanceView.Msg

  override def update(msg: InstanceView.Msg): (InstanceView, Cmd[IO, InstanceView.Msg]) = msg match {
    case InstanceView.Msg.ForContent(inner) =>
      val (av, cmd) = content.update(inner)
      this.copy(content = av) -> cmd.map(InstanceView.Msg.ForContent(_))
    case InstanceView.Msg.Refresh            =>
      this -> content.refresh.map(InstanceView.Msg.ForContent(_))
  }

  override def view: Html[InstanceView.Msg] =
    div(cls:="content")(
      div(cls := "is-flex is-justify-content-space-between is-align-items-center mt-4")(
        p(strong(s"Instance: "), text(instanceId)),
        button(
          cls := s"button is-small is-info ${if content.isLoading then "is-loading" else ""}",
          onClick(InstanceView.Msg.Refresh),
          disabled(content.isLoading),
        )("Refresh"),
      ),
      p(strong(s"Template: "), text(workflowId)),
      content.view.map(InstanceView.Msg.ForContent(_)),
    )
}

object InstanceView {

  // Inner content that actually renders the instance details
  final case class Content(instance: WorkflowInstance, diagramView: MermaidDiagramView, signalsView: SignalsView)
      extends Component {
    override type Self = Content
    override type Msg  = Content.Msg

    override def update(msg: Content.Msg): (Content, Cmd[IO, Content.Msg]) = msg match {
      case Content.Msg.ForDiagram(msg)     =>
        val (newDiagramView, cmd) = diagramView.update(msg)
        this.copy(diagramView = newDiagramView) -> cmd.map(Content.Msg.ForDiagram(_))
      case Content.Msg.ForSignalsView(msg) =>
        val (newCmp, cmd) = signalsView.update(msg)
        this.copy(signalsView = newCmp) -> cmd.map(Content.Msg.ForSignalsView(_))
    }

    private def sectionHeader(text: String) = h4(cls := "mt-4")(text)

    override def view: Html[Content.Msg] =
      div(cls := "content mt-4")(
        sectionHeader("State"),
        instanceStateView,
        sectionHeader("Expected Signals"),
        signalsView.view.map(Content.Msg.ForSignalsView(_)),
        sectionHeader("Progress"),
        progressVisualizationView,
      )

    private def instanceStateView: Html[Content.Msg] = {
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

    private def progressVisualizationView: Html[Content.Msg] =
      div(
        diagramView.view.map(Content.Msg.ForDiagram(_)),
        div(cls := "field is-grouped mt-4")(
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

  object Content {
    enum Msg {
      case ForDiagram(msg: MermaidDiagramView.Msg)
      case ForSignalsView(msg: SignalsView.Msg)
    }

    def initial(instance: WorkflowInstance) = {
      val (mermaidView, cmd) = MermaidDiagramView.initial(instance.mermaidCode)
      Content(instance, mermaidView, SignalsView(instance, None)) -> cmd.map(Msg.ForDiagram(_))
    }
  }

  def initial(workflowId: String, instanceId: String): (InstanceView, Cmd[IO, InstanceView.Msg]) = {
    val (av, start) = AsyncView.empty(Http.getInstance(workflowId, instanceId), Content.initial)
    InstanceView(workflowId, instanceId, av) -> start.map(Msg.ForContent(_))
  }

  enum Msg {
    case ForContent(msg: AsyncView.Msg[Content])
    case Refresh
  }
}
