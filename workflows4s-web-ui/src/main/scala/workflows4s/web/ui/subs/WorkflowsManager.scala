package workflows4s.web.ui.subs

import cats.effect.IO
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.impl.cats.FetchCatsBackend
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.models.WorkflowDefinition
import workflows4s.web.ui.Msg as RootMsg

final case class WorkflowsManager(
    workflows: List[WorkflowDefinition],
    state: WorkflowsManager.State,
    selectedWorkflowId: Option[String],
) {
  def update(msg: WorkflowsManager.Msg): (WorkflowsManager, Cmd[IO, RootMsg]) = msg match {
    case WorkflowsManager.Msg.Load =>
      val updated = this.copy(state = WorkflowsManager.State.Loading)
      val cmd     = WorkflowsManager.Http.loadWorkflows.map(RootMsg.ForWorkflows.apply)
      (updated, cmd)

    case WorkflowsManager.Msg.Loaded(Right(wfs)) =>
      val updated = this.copy(state = WorkflowsManager.State.Ready, workflows = wfs)
      (updated, Cmd.None)

    case WorkflowsManager.Msg.Loaded(Left(err)) =>
      val updated = this.copy(state = WorkflowsManager.State.Failed(err))
      (updated, Cmd.None)

    case WorkflowsManager.Msg.Select(workflowId) =>
      val updated = this.copy(selectedWorkflowId = Some(workflowId))
      val cmd     = Cmd.emit(RootMsg.ForInstances(InstancesManager.Msg.Reset))
      (updated, cmd)
  }

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
                    cls := (if selectedWorkflowId.contains(wf.id) then "is-active" else ""),
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
  def initial(toMsg: Msg => RootMsg): (WorkflowsManager, Cmd[IO, RootMsg]) = {
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