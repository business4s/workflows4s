package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}

import workflows4s.web.api.model.WorkflowInstance
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.util.{AsyncView, Component, JsonView, JsonViewMsg}

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
      val newThis   = this.copy(content = av)
      val mappedCmd = cmd.map(InstanceView.Msg.ForContent(_))
      inner match {
        case AsyncView.Msg.Propagate(InstanceView.Content.Msg.RequestRefresh) =>
          newThis -> Cmd.Batch(mappedCmd, content.refresh.map(InstanceView.Msg.ForContent(_)))
        case _                                                                =>
          newThis -> mappedCmd
      }
    case InstanceView.Msg.Refresh           =>
      this -> content.refresh.map(InstanceView.Msg.ForContent(_))
  }

  override def view: Html[InstanceView.Msg] =
    div(cls := "content")(
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
  final case class Content(instance: WorkflowInstance, diagramView: MermaidDiagramView, signalsView: SignalsView, jsonView: JsonView)
      extends Component {
    override type Self = Content
    override type Msg  = Content.Msg

    override def update(msg: Content.Msg): (Content, Cmd[IO, Content.Msg]) = msg match {
      case Content.Msg.ForDiagram(msg)     =>
        val (newDiagramView, cmd) = diagramView.update(msg)
        this.copy(diagramView = newDiagramView) -> cmd.map(Content.Msg.ForDiagram(_))
      case Content.Msg.ForSignalsView(msg) =>
        val (newCmp, cmd) = signalsView.update(msg)
        val newThis       = this.copy(signalsView = newCmp)
        val mappedCmd     = cmd.map(Content.Msg.ForSignalsView(_))
        msg match {
          case SignalsView.Msg.RefreshInstance => newThis -> Cmd.Batch(mappedCmd, Cmd.Emit(Content.Msg.RequestRefresh))
          case _                               => newThis -> mappedCmd
        }
      case Content.Msg.ForJsonView(msg)    =>
        val (newJsonView, cmd) = jsonView.update(msg)
        this.copy(jsonView = newJsonView) -> cmd.map(Content.Msg.ForJsonView(_))
      case Content.Msg.RequestRefresh      =>
        this -> Cmd.None
    }

    private def sectionHeader(text: String) = h4(cls := "mt-4")(text)

    override def view: Html[Content.Msg] =
      div(cls := "content mt-4")(
        sectionHeader("State"),
        jsonView.view.map(Content.Msg.ForJsonView(_)),
        sectionHeader("Expected Signals"),
        signalsView.view.map(Content.Msg.ForSignalsView(_)),
        sectionHeader("Progress"),
        progressVisualizationView,
      )

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
      case ForJsonView(msg: JsonViewMsg)
      case RequestRefresh
    }

    def initial(instance: WorkflowInstance) = {
      val (mermaidView, cmd1) = MermaidDiagramView.initial(instance.mermaidCode)
      val (jsonView, cmd2)    = JsonView.initial("state-json-view", instance.state)
      val content             = Content(instance, mermaidView, SignalsView(instance, None), jsonView)
      content -> Cmd.merge(cmd1.map(Msg.ForDiagram(_)), cmd2.map(Msg.ForJsonView(_)))
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
