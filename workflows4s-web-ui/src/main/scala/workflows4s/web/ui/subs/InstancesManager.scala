package workflows4s.web.ui.subs

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.WorkflowInstance
import workflows4s.web.ui.components.ReusableViews

final case class InstancesManager(
    instanceIdInput: String,
    state: InstancesManager.State,
    showJsonState: Boolean,
) {
  def update(msg: InstancesManager.Msg): (InstancesManager, Cmd[IO, InstancesManager.Msg]) = msg match {
    case InstancesManager.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case InstancesManager.Msg.LoadInstance(workflowId) =>
      if instanceIdInput.trim.isEmpty then {
        (this.copy(state = InstancesManager.State.Failed("Instance ID cannot be empty.")), Cmd.None)
      } else {
        (this.copy(state = InstancesManager.State.Loading), InstancesManager.Http.loadInstance(workflowId, instanceIdInput))
      }

    case InstancesManager.Msg.InstanceLoaded(Right(instance)) =>
      (this.copy(state = InstancesManager.State.Success(instance)), Cmd.None)

    case InstancesManager.Msg.InstanceLoaded(Left(err)) =>
      (this.copy(state = InstancesManager.State.Failed(err)), Cmd.None)

    case InstancesManager.Msg.ToggleJsonState =>
      (this.copy(showJsonState = !showJsonState), Cmd.None)

    case InstancesManager.Msg.Reset =>
      (InstancesManager.initial, Cmd.None)
  }

  def view(selectedWorkflowId: Option[String]): Html[InstancesManager.Msg] =
    div(cls := "column")(
      selectedWorkflowId match {
        case None =>
          div(cls := "box has-text-centered p-6")(
            p("Select a workflow from the menu to get started."),
          )

        case Some(wfId) =>
          div(cls := "box")(
            instanceInputView(wfId),
            state match {
              case InstancesManager.State.Loading =>
                section(cls := "section is-medium has-text-centered")(
                  p(cls := "title is-4")("Fetching instance details..."),
                )

              case InstancesManager.State.Failed(reason) =>
                div(cls := "notification is-danger is-light mt-4")(text(reason))

              case InstancesManager.State.Success(instance) =>
                instanceDetailsView(instance)

              case InstancesManager.State.Ready =>
                div()
            },
          )
      },
    )

  private def instanceInputView(workflowId: String): Html[InstancesManager.Msg] =
    div(cls := "field is-grouped")(
      div(cls := "control is-expanded")(
        label(cls := "label")("Instance ID"),
        input(
          cls         := "input",
          placeholder := "Enter instance ID",
          value       := instanceIdInput,
          onInput(InstancesManager.Msg.InstanceIdChanged(_)),
        ),
      ),
      div(cls := "control")(
        label(cls := "label")(" "),
        button(
          cls := s"button is-primary ${if state == InstancesManager.State.Loading then "is-loading" else ""}",
          onClick(InstancesManager.Msg.LoadInstance(workflowId)),
          disabled(state == InstancesManager.State.Loading),
        )("Load"),
      ),
    )

  private def instanceDetailsView(instance: WorkflowInstance): Html[InstancesManager.Msg] =
    div(cls := "content mt-4")(
      h3(s"Details for: ${instance.id}"),
      ReusableViews.instanceField("Definition", span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      button(
        cls := "button is-info is-small mt-4",
        onClick(InstancesManager.Msg.ToggleJsonState),
      )(if showJsonState then "Hide State" else "Show State"),
      if showJsonState then jsonStateViewer(instance) else div(),
    )

  private def jsonStateViewer(instance: WorkflowInstance): Html[InstancesManager.Msg] =
    pre(
      code(instance.state.map(_.spaces2).getOrElse("No state available.")),
    )
}

object InstancesManager {
  def initial: InstancesManager =
    InstancesManager(
      instanceIdInput = "",
      state = State.Ready,
      showJsonState = false,
    )

  enum State {
    case Ready
    case Loading
    case Failed(reason: String)
    case Success(instance: WorkflowInstance)
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(workflowId: String)
    case InstanceLoaded(result: Either[String, WorkflowInstance])
    case ToggleJsonState
    case Reset
  }

  object Http {

    def loadInstance(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http
          .getInstance(workflowId, instanceId)
          .map(res => Msg.InstanceLoaded(res.asRight))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage))),
      )
    }
  }
}
