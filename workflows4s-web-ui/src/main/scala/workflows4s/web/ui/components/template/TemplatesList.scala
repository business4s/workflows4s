package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.util.AsyncView

final case class TemplatesList(
                                state: AsyncView.For[TemplateSelector],
) {

  def update(msg: TemplatesList.Msg): (TemplatesList, Cmd[IO, TemplatesList.Msg]) = msg match {
    case TemplatesList.Msg.ForSelector(msg) =>
      val (newState, cmd) = state.update(msg)
      this.copy(state = newState) -> cmd.map(TemplatesList.Msg.ForSelector(_))
  }

  def view: Html[TemplatesList.Msg] =
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
                onClick(TemplatesList.Msg.ForSelector(AsyncView.Msg.Start())),
                disabled(state.isLoading),
              )("Refresh"),
            ),
          ),
          state.view.map(TemplatesList.Msg.ForSelector(_)),
        )
      },
    )
}

object TemplatesList {
  def initial: (TemplatesList, Cmd[IO, Msg]) = {
    val (selectorAsync, start) = AsyncView.empty_(Http.listDefinitions, TemplateSelector(_, None))
    (TemplatesList(state = selectorAsync), start.map(Msg.ForSelector(_)))
  }

  enum Msg {
    case ForSelector(msg: AsyncView.Msg[TemplateSelector])
  }

}
