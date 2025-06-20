package workflows4s.web.ui.components

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.ui.Msg
import workflows4s.web.ui.models.InstanceStatus

object ReusableViews {

  def headerView: Html[Msg] =
    div(
      style := """
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        color: white;
        text-align: center;
        padding: 2rem 0;
        margin-bottom: 2rem;
      """
    )(
      h1(
        style := "margin: 0; font-size: 2.2rem; font-weight: 300;"
      )("🚀 Workflows4s Management Dashboard")
    )

  def loadingSpinner(text: String): Html[Msg] =
    div(style := "text-align: center; padding: 2rem; color: #6c757d;")(
      div(style := "font-size: 1.5rem; margin-bottom: 1rem;")("⏳"),
      div(text),
    )

  def errorView(error: String): Html[Msg] =
    div(
      style := """
        background: #f8d7da;
        color: #721c24;
        border: 1px solid #f5c6cb;
        padding: 1rem;
        border-radius: 8px;
        margin: 1rem 0;
      """
    )(s"Error: $error")

  def statusBadge(status: InstanceStatus): Html[Msg] = {
    val (bgColor, textColor) = status match {
      case InstanceStatus.Running   => ("#28a745", "white")
      case InstanceStatus.Completed => ("#007bff", "white")
      case InstanceStatus.Failed    => ("#dc3545", "white")
      case InstanceStatus.Paused    => ("#ffc107", "black")
    }

    span(
      style := s"""
        background: $bgColor;
        color: $textColor;
        padding: 0.25rem 0.75rem;
        border-radius: 50px;
        font-size: 0.8rem;
        font-weight: 600;
      """
    )(status.toString)
  }

  def instanceField(label: String, value: Html[Msg]): Html[Msg] =
    div(
      style := "display: flex; justify-content: space-between; align-items: center; padding: 0.5rem 0; border-bottom: 1px solid #eee;"
    )(
      span(style := "font-weight: 600; color: #495057;")(s"$label:"),
      value,
    )
}