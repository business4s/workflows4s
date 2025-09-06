package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.{AsyncView, Component}

case class WorkflowSelector(defs: List[WorkflowDefinition], selectedWorkflowId: Option[String]) extends Component {
  override type Self = WorkflowSelector
  override type Msg  = WorkflowSelector.Msg

  override def update(msg: WorkflowSelector.Msg): (WorkflowSelector, Cmd[IO, WorkflowSelector.Msg]) = msg match {
    case WorkflowSelector.Msg.Select(workflowId) => this.copy(selectedWorkflowId = Some(workflowId)) -> Cmd.None
  }

  override def view: Html[WorkflowSelector.Msg] =
    ul(cls := "menu-list")(
      defs.map { wf =>
        li(
          a(
            cls := (if selectedWorkflowId.contains(wf.id) then "is-active" else ""),
            onClick(WorkflowSelector.Msg.Select(wf.id)),
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
    case Select(workflowId: String)
  }
}

final case class WorkflowsManager(
    state: AsyncView[List[WorkflowDefinition], WorkflowSelector, WorkflowSelector.Msg],
) {

  def update(msg: WorkflowsManager.Msg): (WorkflowsManager, Cmd[IO, WorkflowsManager.Msg]) = msg match {
    case WorkflowsManager.Msg.ForSelector(msg) =>
      val (newState, cmd) = state.update(msg)
      this.copy(state = newState) -> cmd.map(WorkflowsManager.Msg.ForSelector(_))
  }

  def view: Html[WorkflowsManager.Msg] =
    aside(cls := "column is-one-quarter")(
      div(cls := "box") {
        nav(cls := "menu p-4")(
          div(cls := "level")(
            div(cls := "level-left")(
              p(cls := "menu-label")("Available Workflows"),
            ),
            div(cls := "level-right")(
              button(
                cls := s"button is-small is-primary ${if state == WorkflowsManager.State.Loading then "is-loading" else ""}",
                onClick(WorkflowsManager.Msg.ForSelector(AsyncView.Msg.Start)),
                disabled(state == WorkflowsManager.State.Loading),
              )("Refresh"),
            ),
          ),
          state.view.map(WorkflowsManager.Msg.ForSelector(_)),
        )
      },
    )
}

object WorkflowsManager {
  def initial: (WorkflowsManager, Cmd[IO, Msg]) = {
    val (selectorAsync, start) = AsyncView.empty_(workflows4s.web.ui.http.Http.listDefinitions, WorkflowSelector(_, None))
    (WorkflowsManager(state = selectorAsync), start.map(Msg.ForSelector(_)))
  }

  enum State {
    case Initializing
    case Loading
    case Ready
    case Failed(reason: String)
  }

  enum Msg {
    case ForSelector(msg: AsyncView.Msg[List[WorkflowDefinition], WorkflowSelector.Msg])
  }

}
