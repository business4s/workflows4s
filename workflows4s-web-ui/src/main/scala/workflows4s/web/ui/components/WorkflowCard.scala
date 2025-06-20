package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.ui.Msg
import workflows4s.web.ui.models.WorkflowDefinition

object WorkflowCard {

  private def workflowIcon(workflowId: String): String =
    workflowId match {
      case id if id.contains("course")       => "🎓"
      case id if id.contains("pull-request") => "🔀"
      case id if id.contains("withdrawal")   => "💸"
      case _                                 => "⚙️"
    }

  def view(workflow: WorkflowDefinition, isSelected: Boolean): Html[Msg] = {
    val selectedStyles =
      if (isSelected)
        List(
          style("border-color", "#667eea"),
          style("box-shadow", "0 8px 25px rgba(102, 126, 234, 0.3)"),
          style("transform", "translateY(-2px)"),
        )
      else List.empty

    div(
      (List(
        style := """
          background: white;
          border-radius: 12px;
          padding: 1.5rem;
          border: 2px solid #e9ecef;
          transition: all 0.2s ease-in-out;
          cursor: pointer;
        """,
        onClick(Msg.WorkflowSelected(workflow.id)),
      ) ++ selectedStyles)*
    )(
      div(
        style := "display: flex; align-items: center; margin-bottom: 1rem;",
      )(
        div(style := "font-size: 2rem; margin-right: 1rem;")(workflowIcon(workflow.id)),
        h3(style := "margin: 0; color: #343a40; font-size: 1.2rem;")(workflow.name),
      ),
      p(style := "color: #6c757d; font-size: 0.9rem; margin: 0;")(s"ID: ${workflow.id}"),
    )
  }
}