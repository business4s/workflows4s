package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.util.Component

case class TemplateSelector(defs: List[WorkflowDefinition], selectedWorkflowId: Option[String]) extends Component {
  override type Self = TemplateSelector
  override type Msg  = TemplateSelector.Msg

  override def update(msg: TemplateSelector.Msg): (TemplateSelector, Cmd[IO, TemplateSelector.Msg]) = msg match {
    case TemplateSelector.Msg.Select(workflow) => this.copy(selectedWorkflowId = Some(workflow.id)) -> Cmd.None
  }

  override def view: Html[TemplateSelector.Msg] =
    ul(cls := "menu-list")(
      defs.map { wf =>
        li(
          a(
            cls := (if selectedWorkflowId.contains(wf.id) then "is-active" else ""),
            onClick(TemplateSelector.Msg.Select(wf)),
          )(
            div(
              strong(wf.name),
              br(),
              span(cls := "is-size-7")(wf.id),
            ),
          ),
        )
      },
    )

}

object TemplateSelector {
  enum Msg {
    case Select(workflow: WorkflowDefinition)
  }
}
