package workflows4s.web.ui.subs

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.components.InstanceView
import workflows4s.web.ui.components.util.AsyncView
import workflows4s.web.ui.http.Http

import java.util.UUID

final case class InstancesManager(
    templateId: String,
    instanceIdInput: String,
    state: Option[AsyncView.For[InstanceView]],
) {

  def update(msg: InstancesManager.Msg): (InstancesManager, Cmd[IO, InstancesManager.Msg]) = msg match {
    case InstancesManager.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case InstancesManager.Msg.LoadInstance(instanceId) =>
      val (asyncView, asyncCmd) = AsyncView.empty(Http.getInstance(templateId, instanceId), instance => InstanceView.initial(instance))
      this.copy(state = asyncView.some) -> asyncCmd.map(InstancesManager.Msg.ForInstance(_))

    case InstancesManager.Msg.ForInstance(subMsg) =>
      state match {
        case Some(value) =>
          val (newState, cmd) = value.update(subMsg)
          this.copy(state = newState.some) -> cmd.map(InstancesManager.Msg.ForInstance(_))
        case None        => ???
      }
  }

  def view: Html[InstancesManager.Msg] =
    div(
      div(cls := "tabs")(
        ul(
          li(cls := "is-active")(a("Instances")),
          li()(a("Definition")),
        ),
      ),
      instanceInputView,
      state match {
        case Some(value) => value.view.map(InstancesManager.Msg.ForInstance(_))
        case None        => div()
      },
    )

  private def instanceInputView: Html[InstancesManager.Msg] =
    div(
      div(cls := "field is-grouped")(
        div(cls := "control is-expanded")(
          label(cls := "label")("Instance ID"),
          input(
            cls         := "input",
            placeholder := "Enter instance ID (e.g., inst-1)",
            value       := instanceIdInput,
            onInput(InstancesManager.Msg.InstanceIdChanged(_)),
          ),
        ),
      ),
      div(cls := "field is-grouped mt-2")(
        div(cls := "control")(
          button(
            cls := s"button is-primary ${if state.exists(_.isLoading) then "is-loading" else ""}",
            onClick(InstancesManager.Msg.LoadInstance(instanceIdInput)),
            disabled(state.exists(_.isLoading)),
          )("Load"),
        ),
        div(cls := "control")(
          button(
            cls := s"button is-info is-outlined ${if state.exists(_.isLoading) then "is-loading" else ""}",
            onClick(InstancesManager.Msg.LoadInstance(s"test-instance-${UUID.randomUUID()}")),
            disabled(state.exists(_.isLoading)),
          )("Create Test Instance"),
        ),
      ),
    )

}

object InstancesManager {
  def initial(templateId: String): InstancesManager =
    InstancesManager(
      templateId = templateId,
      instanceIdInput = "",
      state = None,
    )

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(instanceId: String)
    case ForInstance(msg: AsyncView.Msg[InstanceView])
  }

}
