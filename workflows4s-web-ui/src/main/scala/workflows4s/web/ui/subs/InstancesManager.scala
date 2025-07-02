package workflows4s.web.ui.subs

import cats.effect.IO
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.impl.cats.FetchCatsBackend
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.models.WorkflowInstance

object InstancesManager {

  // MODEL
  final case class Model(
      instanceIdInput: String = "",
      state: State = State.Ready,
      showJsonState: Boolean = false,
  ) {
    def update(msg: Msg): (Model, Cmd[IO, Msg]) = msg match {
      case Msg.InstanceIdChanged(id) =>
        (this.copy(instanceIdInput = id), Cmd.None)

      case Msg.LoadInstance(workflowId) =>
        if (instanceIdInput.trim.isEmpty) {
          (this.copy(state = State.Failed("Instance ID cannot be empty.")), Cmd.None)
        } else {
          (this.copy(state = State.Loading), Http.loadInstance(workflowId, instanceIdInput))
        }

      case Msg.InstanceLoaded(Right(instance)) =>
        (this.copy(state = State.Success(instance)), Cmd.None)

      case Msg.InstanceLoaded(Left(err)) =>
        (this.copy(state = State.Failed(err)), Cmd.None)

      case Msg.ToggleJsonState =>
        (this.copy(showJsonState = !showJsonState), Cmd.None)

      case Msg.Reset =>
        (Model(), Cmd.None)
    }
  }

  enum State {
    case Ready
    case Loading
    case Failed(reason: String)
    case Success(instance: WorkflowInstance)
  }

  // MSG
  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(workflowId: String)
    case InstanceLoaded(result: Either[String, WorkflowInstance])
    case ToggleJsonState
    case Reset
  }

  // VIEW
  def view(model: Model, selectedWorkflowId: Option[String]): Html[Msg] =
    div(cls := "column")(
      selectedWorkflowId match {
        case None =>
          div(cls := "box has-text-centered p-6")(
            p("Select a workflow from the menu to get started."),
          )
        case Some(wfId) =>
          div(cls := "box")(
            instanceInputView(model, wfId),
            model.state match {
              case State.Loading =>
                section(cls := "section is-medium has-text-centered")(
                  p(cls := "title is-4")("Fetching instance details..."),
                )
              case State.Failed(reason) =>
                div(cls := "notification is-danger is-light mt-4")(text(reason))
              case State.Success(instance) =>
                instanceDetailsView(model, instance)
              case State.Ready =>
                div()
            },
          )
      },
    )

  private def instanceInputView(model: Model, workflowId: String): Html[Msg] =
    div(cls := "field is-grouped")(
      div(cls := "control is-expanded")(
        label(cls := "label")("Instance ID"),
        input(
          cls         := "input",
          placeholder := "Enter instance ID",
          value       := model.instanceIdInput,
          onInput(Msg.InstanceIdChanged(_)),
        ),
      ),
      div(cls := "control")(
        label(cls := "label")(" "), // Empty label for alignment
        button(
          cls     := s"button is-primary ${if (model.state == State.Loading) "is-loading" else ""}",
          onClick(Msg.LoadInstance(workflowId)),
          disabled(model.state == State.Loading),
        )("Load"),
      ),
    )

  private def instanceDetailsView(model: Model, instance: WorkflowInstance): Html[Msg] =
    div(cls := "content mt-4")(
      h3(s"Details for: ${instance.id}"),
      ReusableViews.instanceField("Definition", span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      button(
        cls     := "button is-info is-small mt-4",
        onClick(Msg.ToggleJsonState),
      )(if (model.showJsonState) "Hide State" else "Show State"),
      if (model.showJsonState) jsonStateViewer(instance) else div(),
    )

  private def jsonStateViewer(instance: WorkflowInstance): Html[Msg] =
    pre(
      code(instance.state.map(_.spaces2).getOrElse("No state available.")),
    )

  // HTTP
  object Http {
    private val backend = FetchCatsBackend[IO]()
    private val baseUri = uri"http://localhost:8081/api/v1"

    def loadInstance(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      val request = basicRequest
        .get(baseUri.addPath("definitions", workflowId, "instances", instanceId))
        .response(asJson[WorkflowInstance])

      Cmd.Run(
        backend
          .send(request)
          .map(_.body)
          .map(res => Msg.InstanceLoaded(res.left.map(_.toString)))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage))),
      )
    }
  }
}