package workflows4s.web.ui.components.instance

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import forms4s.circe.extractJson
import forms4s.jsonschema.*
import forms4s.tyrian.BulmaFormRenderer
import forms4s.{FormElement, FormElementState, FormElementUpdate}
import io.circe.Json
import tyrian.Html.*
import tyrian.*
import workflows4s.web.api.model.{Signal, SignalRequest, WorkflowInstance}
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.instance.SignalModal.Msg
import workflows4s.web.ui.components.util.{AsyncView, Component}

case class SignalResponseView(response: Json) extends Component.ReadOnly[SignalResponseView] {
  override def view: Html[Nothing] = code(response.spaces2)
}

case class SignalModal(
    wfInstance: WorkflowInstance,
    signal: Signal,
    visible: Boolean,
    formState: FormElementState,
    response: Option[AsyncView.For[SignalResponseView]],
) {

  def view: Elem[SignalModal.Msg] =
    if !visible then Empty
    else
      div(cls := "modal is-active")(
        div(cls := "modal-background", onClick(SignalModal.Msg.Close))(),
        div(cls := "modal-card")(
          header(cls := "modal-card-head")(
            p(cls := "modal-card-title", style(Style("margin-bottom", "0")))("Signal details"),
            button(cls := "delete", onClick(SignalModal.Msg.Close)),
          ),
          section(cls := "modal-card-body")(
            form(
              BulmaFormRenderer.renderForm(formState).map(Msg.ForForm(_)),
              div(cls := "control is-flex is-justify-content-flex-end")(
                button(cls := "button is-primary", onClick(Msg.Send))("Send"),
              ),
              hr(),
              response.map(_.view.map(Msg.ForResult(_))).getOrElse(Empty),
            ),
          ),
        ),
      )

  def update(msg: SignalModal.Msg): (SignalModal, Cmd[IO, SignalModal.Msg]) = msg match {
    case Msg.Close          => (this.copy(visible = false), Cmd.None)
    case Msg.ForForm(msg)   => (this.copy(formState = formState.update(msg)), Cmd.None)
    case Msg.ForResult(msg) =>
      response match {
        case Some(response) =>
          val (cmp, cmd) = response.update(msg)
          (this.copy(response = Some(cmp)), cmd.map(Msg.ForResult(_)))
        case None           => this -> Cmd.None
      }

    case Msg.Send =>
      val (cmp, cmd) =
        AsyncView.empty_(
          Http.sendSignal(SignalRequest(wfInstance.templateId, wfInstance.id, signal.id, formState.extractJson)),
          SignalResponseView(_),
        )
      this.copy(response = cmp.some) -> cmd.map(Msg.ForResult(_))
  }

}

object SignalModal {

  def apply(wfInstance: WorkflowInstance, signal: Signal, visible: Boolean): SignalModal = {
    val form      = FormElement.fromJsonSchema(signal.requestSchema.get) // TODO handle lack of schema properly
    val formState = FormElementState.empty(form)
    SignalModal(wfInstance, signal, visible, formState, None) // TODO handle lack of response view
  }

  enum Msg {
    case Close
    case Send
    case ForForm(msg: FormElementUpdate)
    case ForResult(msg: AsyncView.Msg[SignalResponseView])
  }

}
