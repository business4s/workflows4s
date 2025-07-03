package workflows4s.web.ui.subs

import cats.effect.IO
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.impl.cats.FetchCatsBackend
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.models.WorkflowDefinition

// Renamed from Model to WorkflowsManager (the class represents the entire subsystem)
final case class WorkflowsManager(
    workflows: List[WorkflowDefinition],
    state: WorkflowsManager.State,
    selectedWorkflowId: Option[String],
) {
  def update(msg: WorkflowsManager.Msg): (WorkflowsManager, Cmd[IO, WorkflowsManager.Msg]) = msg match {
    case WorkflowsManager.Msg.Load =>
      (this.copy(state = WorkflowsManager.State.Loading), WorkflowsManager.Http.loadWorkflows)

    case WorkflowsManager.Msg.Loaded(Right(wfs)) =>
      (this.copy(state = WorkflowsManager.State.Ready, workflows = wfs), Cmd.None)

    case WorkflowsManager.Msg.Loaded(Left(err)) =>
      (this.copy(state = WorkflowsManager.State.Failed(err)), Cmd.None)

    case WorkflowsManager.Msg.Select(workflowId) =>
      (this.copy(selectedWorkflowId = Some(workflowId)), Cmd.None)
  }

  // Moved view from companion object to the class
  def view: Html[WorkflowsManager.Msg] =
    aside(cls := "column is-one-quarter")(
      nav(cls := "menu p-4")(
        p(cls := "menu-label")("Available Workflows"),
        state match {
          case WorkflowsManager.State.Initializing | WorkflowsManager.State.Loading =>
            p(cls := "menu-list")("Loading workflows...")
          case WorkflowsManager.State.Failed(reason) =>
            div(cls := "notification is-danger is-light")(text(reason))
          case WorkflowsManager.State.Ready =>
            ul(cls := "menu-list")(
              workflows.map { wf =>
                li(
                  a(
                    cls     := (if (selectedWorkflowId.contains(wf.id)) "is-active" else ""),
                    onClick(WorkflowsManager.Msg.Select(wf.id)),
                  )(wf.name),
                )
              },
            )
        },
      ),
    )
}

object WorkflowsManager {
  def initial[A](toMsg: Msg => A): (WorkflowsManager, Cmd[IO, A]) = {
    val manager = WorkflowsManager(
      workflows = Nil,
      state = State.Initializing,
      selectedWorkflowId = None
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

  // HTTP
  object Http {
    private val backend = FetchCatsBackend[IO]()
    private val baseUri = uri"http://localhost:8081/api/v1"

    def loadWorkflows: Cmd[IO, Msg] = {
      val request = basicRequest
        .get(baseUri.addPath("definitions"))
        .response(asJson[List[WorkflowDefinition]])

      Cmd.Run(
        backend
          .send(request)
          .map(_.body)
          .map(res => Msg.Loaded(res.left.map(_.toString)))
          .handleError(err => Msg.Loaded(Left(err.getMessage))),
      )
    }
  }
}