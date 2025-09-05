package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.api.model.InstanceStatus

object ReusableViews {

  def headerView: Html[Nothing] =
    section(cls := "hero is-primary")(
      div(cls := "hero-body")(
        div(cls := "container")(
          h1(cls := "title")("Workflows4s"),
          h2(cls := "subtitle")("A lightweight workflow engine for Scala"),
        ),
      ),
    )

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

  def statusBadge(status: InstanceStatus): Html[Nothing] = {
    val badgeClass = status match {
      case InstanceStatus.Running   => "is-info"
      case InstanceStatus.Completed => "is-success"
      case InstanceStatus.Failed    => "is-danger"
      case InstanceStatus.Paused    => "is-warning" // Fixed typo here
    }
    span(cls := s"tag $badgeClass")(status.toString)
  }

  def instanceField(label: String, value: Html[Nothing]): Html[Nothing] =
    div(cls := "field")(
      Html.label(cls := "label")(label),
      div(cls := "control")(value),
    )

}
