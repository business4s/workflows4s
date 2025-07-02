package workflows4s.web.ui.subs

import cats.effect.IO
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.impl.cats.FetchCatsBackend
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.models.WorkflowDefinition

object WorkflowsManager {

  // MODEL
  final case class Model(
      workflows: List[WorkflowDefinition] = Nil,
      state: State = State.Initializing,
      selectedWorkflowId: Option[String] = None,
  ) {
    def update(msg: Msg): (Model, Cmd[IO, Msg]) = msg match {
      case Msg.Load =>
        (this.copy(state = State.Loading), Http.loadWorkflows)

      case Msg.Loaded(Right(wfs)) =>
        (this.copy(state = State.Ready, workflows = wfs), Cmd.None)

      case Msg.Loaded(Left(err)) =>
        (this.copy(state = State.Failed(err)), Cmd.None)

      case Msg.Select(workflowId) =>
        (this.copy(selectedWorkflowId = Some(workflowId)), Cmd.None)
    }
  }

  enum State {
    case Initializing
    case Loading
    case Ready
    case Failed(reason: String)
  }

  // MSG
  enum Msg {
    case Load
    case Loaded(result: Either[String, List[WorkflowDefinition]])
    case Select(workflowId: String)
  }

  // VIEW
  def view(model: Model): Html[Msg] =
    aside(cls := "column is-one-quarter")(
      nav(cls := "menu p-4")(
        p(cls := "menu-label")("Available Workflows"),
        model.state match {
          case State.Initializing | State.Loading =>
            p(cls := "menu-list")("Loading workflows...")
          case State.Failed(reason) =>
            div(cls := "notification is-danger is-light")(text(reason))
          case State.Ready =>
            ul(cls := "menu-list")(
              model.workflows.map { wf =>
                li(
                  a(
                    cls     := (if (model.selectedWorkflowId.contains(wf.id)) "is-active" else ""),
                    onClick(Msg.Select(wf.id)),
                  )(wf.name),
                )
              },
            )
        },
      ),
    )

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