package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*

object ReusableViews {

  def loadingSpinner(text: String): Html[Nothing] =
    div(cls := "has-text-centered p-4")(
      button(cls := "button is-loading is-large is-ghost"),
      p(cls := "is-size-4 mt-2")(text),
    )

  def errorView(error: String): Html[Nothing] =
    div(cls := "notification is-danger is-light")(
      button(cls := "delete"),
      text(error),
    )

  def inlineField(label: String, value: String): Html[Nothing] =
    div()(
      strong()(label),text(": "), text(value)
    )

}
