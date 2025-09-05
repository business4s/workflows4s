package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.ReusableViews

final case class WorkflowsManager(
    workflows: List[WorkflowDefinition],
    state: WorkflowsManager.State,
    selectedWorkflowId: Option[String],
) {

  def update(msg: WorkflowsManager.Msg): (WorkflowsManager, Cmd[IO, WorkflowsManager.Msg]) = msg match {
    case WorkflowsManager.Msg.Load =>
      val updated = this.copy(state = WorkflowsManager.State.Loading)
      val cmd     = WorkflowsManager.Http.loadWorkflows
      (updated, cmd)

    case WorkflowsManager.Msg.Loaded(Right(workflows)) =>
      val updated = this.copy(workflows = workflows, state = WorkflowsManager.State.Ready)
      (updated, Cmd.None)

    case WorkflowsManager.Msg.Loaded(Left(err)) =>
      val updated = this.copy(state = WorkflowsManager.State.Failed(err))
      (updated, Cmd.None)

    case WorkflowsManager.Msg.Select(workflowId) =>
      val updated = this.copy(selectedWorkflowId = Some(workflowId))
      (updated, Cmd.None)
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
                onClick(WorkflowsManager.Msg.Load),
                disabled(state == WorkflowsManager.State.Loading),
              )("Refresh"),
            ),
          ),
          state match {
            case WorkflowsManager.State.Initializing =>
              p(cls := "menu-list")("Initializing...")

            case WorkflowsManager.State.Loading =>
              ReusableViews.loadingSpinner("Loading workflows...")

            case WorkflowsManager.State.Failed(reason) =>
              div(cls := "notification is-danger is-light")(text(reason))

            case WorkflowsManager.State.Ready =>
              if workflows.isEmpty then div(cls := "notification is-info is-light")(
                p("No workflows found."),
                p(cls := "is-size-7 mt-2")("Make sure the API server is running."),
              )
              else
                ul(cls := "menu-list")(
                  workflows.map { wf =>
                    li(
                      a(
                        cls := (if selectedWorkflowId.contains(wf.id) then "is-active" else ""),
                        onClick(WorkflowsManager.Msg.Select(wf.id)),
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
          },
        )
      },
    )
}

object WorkflowsManager {
  def initial[M](toMsg: Msg => M): (WorkflowsManager, Cmd[IO, M]) = {
    val manager = WorkflowsManager(
      workflows = Nil,
      state = State.Initializing,
      selectedWorkflowId = None,
    )
    val loadCmd = Http.loadWorkflows.map(toMsg)
    (manager, loadCmd)
  }

  enum State {
    case Initializing
    case Loading
    case Ready
    case Failed(reason: String)
  }

  enum Msg {
    case Load
    case Loaded(result: Either[String, List[WorkflowDefinition]])
    case Select(workflowId: String)
  }

  object Http {
    def loadWorkflows: Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http.listDefinitions
          .map(definitions => Msg.Loaded(Right(definitions)))
          .handleError(err => Msg.Loaded(Left(err.getMessage))),
      )
    }
  }
}
