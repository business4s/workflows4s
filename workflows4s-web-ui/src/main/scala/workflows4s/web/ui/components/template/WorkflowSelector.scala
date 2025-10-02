package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.util.Component

case class WorkflowSelector(defs: List[WorkflowDefinition], selectedWorkflowId: Option[String]) extends Component {
  override type Self = WorkflowSelector
  override type Msg  = WorkflowSelector.Msg

  override def update(msg: WorkflowSelector.Msg): (WorkflowSelector, Cmd[IO, WorkflowSelector.Msg]) = msg match {
    case WorkflowSelector.Msg.Select(workflow) => this.copy(selectedWorkflowId = Some(workflow.id)) -> Cmd.None
  }

  override def view: Html[WorkflowSelector.Msg] =
    ul(cls := "menu-list")(
      defs.map { wf =>
        li(
          a(
            cls := (if selectedWorkflowId.contains(wf.id) then "is-active" else ""),
            onClick(WorkflowSelector.Msg.Select(wf)),
          )(
            div(
              strong(wf.name),
              br(),
              span(cls := "is-size-7")(wf.id),
              wf.description
                .map(desc =>
                  div(
                    br(),
                    span(cls := "is-size-7")(desc),
                  ),
                )
                .getOrElse(div()),
            ),
          ),
        )
      },
    )

}

object WorkflowSelector {
  enum Msg {
    case Select(workflow: WorkflowDefinition)
  }
}
