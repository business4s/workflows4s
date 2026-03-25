package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.{ExecutionStatus, WorkflowSearchRequest}
import workflows4s.web.ui.components.util.Component

case class InstancesFilterBar(
    statusFilters: Set[ExecutionStatus],
    sortBy: Option[WorkflowSearchRequest.SortBy],
    pageSize: Int,
) extends Component {

  override type Self = InstancesFilterBar
  override type Msg  = InstancesFilterBar.Msg

  override def update(msg: Msg): (InstancesFilterBar, Cmd[IO, Msg]) = msg match {
    case InstancesFilterBar.Msg.ToggleStatus(status) =>
      val newFilters =
        if statusFilters.contains(status) then statusFilters - status
        else statusFilters + status
      this.copy(statusFilters = newFilters) -> Cmd.None

    case InstancesFilterBar.Msg.SortChanged(newSort) =>
      this.copy(sortBy = newSort) -> Cmd.None

    case InstancesFilterBar.Msg.PageSizeChanged(newSize) =>
      this.copy(pageSize = newSize) -> Cmd.None

    case InstancesFilterBar.Msg.ClearFilters =>
      InstancesFilterBar.default -> Cmd.None
  }

  override def view: Html[Msg] =
    div(cls := "box mb-4")(
      div(cls := "field is-grouped is-grouped-multiline")(
        div(cls := "control")(
          label(cls := "label is-small")("Status"),
          div(cls := "buttons has-addons")(
            statusButton(ExecutionStatus.Running, "Running"),
            statusButton(ExecutionStatus.Awaiting, "Awaiting"),
            statusButton(ExecutionStatus.Finished, "Finished"),
          ),
        ),
        div(cls := "control")(
          label(cls := "label is-small")("Sort"),
          div(cls := "select is-small")(
            select(onInput(value => InstancesFilterBar.Msg.SortChanged(InstancesFilterBar.parseSortByOpt(value))))(
              option(value := "", selected(sortBy.isEmpty))("-- None --"),
              option(value := "CreatedAsc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.CreatedAsc)))("Created ↑"),
              option(value := "CreatedDesc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.CreatedDesc)))("Created ↓"),
              option(value := "UpdatedAsc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.UpdatedAsc)))("Updated ↑"),
              option(value := "UpdatedDesc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.UpdatedDesc)))("Updated ↓"),
              option(value := "WakeupAsc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.WakeupAsc)))("Wakeup ↑"),
              option(value := "WakeupDesc", selected(sortBy.contains(WorkflowSearchRequest.SortBy.WakeupDesc)))("Wakeup ↓"),
            ),
          ),
        ),
        div(cls := "control")(
          label(cls := "label is-small")("Page Size"),
          div(cls := "select is-small")(
            select(onInput(value => InstancesFilterBar.Msg.PageSizeChanged(value.toInt)))(
              option(value := "10", selected(pageSize == 10))("10"),
              option(value := "25", selected(pageSize == 25))("25"),
              option(value := "50", selected(pageSize == 50))("50"),
              option(value := "100", selected(pageSize == 100))("100"),
            ),
          ),
        ),
        div(cls := "control")(
          label(cls := "label is-small")(" "),
          button(cls := "button is-small is-light", onClick(InstancesFilterBar.Msg.ClearFilters))("Clear Filters"),
        ),
      ),
    )

  private def statusButton(status: ExecutionStatus, label: String): Html[Msg] = {
    val isActive = statusFilters.contains(status)
    button(
      cls := s"button is-small ${if isActive then "is-info" else ""}",
      onClick(InstancesFilterBar.Msg.ToggleStatus(status)),
    )(label)
  }

  def toQueryParams: Map[String, String] = {
    val params = scala.collection.mutable.Map[String, String]()
    if statusFilters.nonEmpty then params += "status" -> statusFilters.mkString(",")
    sortBy.foreach(s => params += "sort" -> s.toString)
    if pageSize != 25 then params += "limit"          -> pageSize.toString
    params.toMap
  }
}

object InstancesFilterBar {
  val allowedPageSizes = Set(10, 25, 50, 100)

  def default: InstancesFilterBar = InstancesFilterBar(
    statusFilters = Set(),
    sortBy = Some(WorkflowSearchRequest.SortBy.UpdatedDesc),
    pageSize = 25,
  )

  def fromQueryParams(params: Map[String, String]): InstancesFilterBar = {
    val statusFilters =
      params.get("status").map(_.split(",").flatMap(s => scala.util.Try(ExecutionStatus.valueOf(s)).toOption).toSet).getOrElse(Set.empty)
    val sortBy        = params.get("sort").flatMap(parseSortByOpt).orElse(Some(WorkflowSearchRequest.SortBy.UpdatedDesc))
    val pageSize      = params.get("limit").flatMap(_.toIntOption).filter(allowedPageSizes.contains).getOrElse(25)
    InstancesFilterBar(statusFilters, sortBy, pageSize)
  }

  def parseSortByOpt(value: String): Option[WorkflowSearchRequest.SortBy] = value match {
    case "CreatedAsc"  => Some(WorkflowSearchRequest.SortBy.CreatedAsc)
    case "CreatedDesc" => Some(WorkflowSearchRequest.SortBy.CreatedDesc)
    case "UpdatedAsc"  => Some(WorkflowSearchRequest.SortBy.UpdatedAsc)
    case "UpdatedDesc" => Some(WorkflowSearchRequest.SortBy.UpdatedDesc)
    case "WakeupAsc"   => Some(WorkflowSearchRequest.SortBy.WakeupAsc)
    case "WakeupDesc"  => Some(WorkflowSearchRequest.SortBy.WakeupDesc)
    case _             => None
  }

  enum Msg {
    case ToggleStatus(status: ExecutionStatus)
    case SortChanged(sort: Option[WorkflowSearchRequest.SortBy])
    case PageSizeChanged(size: Int)
    case ClearFilters
  }
}
