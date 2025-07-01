package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.ui.Msg
import workflows4s.web.ui.models.WorkflowDefinition

object SidebarView {
  def view(workflows: List[WorkflowDefinition], selectedWorkflowId: Option[String]): Html[Msg] =
    aside(cls := "column is-one-quarter")(
      nav(cls := "menu p-4")(
        p(cls := "menu-label")("Available Workflows"),
        ul(cls := "menu-list")(
          workflows.map { wf =>
            li(
              a(
                cls     := (if (selectedWorkflowId.contains(wf.id)) "is-active" else ""),
                onClick(Msg.WorkflowSelected(wf.id)),
              )(wf.name),
            )
          },
        ),
      ),
    )
}