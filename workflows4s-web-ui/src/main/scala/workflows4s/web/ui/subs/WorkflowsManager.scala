package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.components.WorkflowSelector
import workflows4s.web.ui.components.util.AsyncView

final case class WorkflowsManager(
    state: AsyncView.For[WorkflowSelector],
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
                cls := s"button is-small is-primary ${if state.isLoading then "is-loading" else ""}",
                onClick(WorkflowsManager.Msg.ForSelector(AsyncView.Msg.Start())),
                disabled(state.isLoading),
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

  enum Msg {
    case ForSelector(msg: AsyncView.Msg[WorkflowSelector])
  }

}
