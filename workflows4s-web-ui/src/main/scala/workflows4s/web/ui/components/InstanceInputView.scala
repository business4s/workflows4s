package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.ui.Msg
import workflows4s.web.ui.models.{AppState, Model}

object InstanceInputView {
  def view(model: Model): Html[Msg] =
    div(cls := "field is-grouped")(
      div(cls := "control is-expanded")(
        label(cls := "label")("Instance ID"),
        input(
          cls     := "input",
          placeholder := "Enter instance ID",
          value       := model.instanceIdInput,
          onInput(Msg.InstanceIdChanged(_)),
        ),
      ),
      div(cls := "control")(
        label(cls := "label")(" "), // Empty label for alignment
        button(
          cls    := s"button is-primary ${if (model.appState == AppState.LoadingInstance) "is-loading" else ""}",
          onClick(Msg.LoadInstance),
          disabled(model.appState == AppState.LoadingInstance),
        )("Load"),
      ),
    )
}