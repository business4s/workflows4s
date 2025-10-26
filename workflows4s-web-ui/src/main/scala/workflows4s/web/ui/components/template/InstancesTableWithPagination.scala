package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.api.model.WorkflowSearchResponse
import workflows4s.web.ui.components.util.Component

case class InstancesTableWithPagination(
    table: SearchResultsTable,
    pagination: PaginationControls,
) extends Component {

  override type Self = InstancesTableWithPagination
  override type Msg  = InstancesTableWithPagination.Msg

  override def update(msg: Msg): (InstancesTableWithPagination, Cmd[IO, Msg]) = msg match {
    case InstancesTableWithPagination.Msg.ForTable(tableMsg) =>
      val (newTable, cmd) = table.update(tableMsg)
      this.copy(table = newTable) -> cmd.map(InstancesTableWithPagination.Msg.ForTable(_))

    case InstancesTableWithPagination.Msg.ForPagination(paginationMsg) =>
      val (newPagination, cmd) = pagination.update(paginationMsg)
      this.copy(pagination = newPagination) -> cmd.map(InstancesTableWithPagination.Msg.ForPagination(_))
  }

  override def view: Html[Msg] =
    div(
      table.view.map(InstancesTableWithPagination.Msg.ForTable(_)),
      pagination.view.map(InstancesTableWithPagination.Msg.ForPagination(_)),
    )
}

object InstancesTableWithPagination {
  def fromResponse(response: WorkflowSearchResponse, pageSize: Int, currentPage: Int): InstancesTableWithPagination = {
    InstancesTableWithPagination(
      table = SearchResultsTable(response.results),
      pagination = PaginationControls(currentPage = currentPage, pageSize = pageSize, totalCount = response.totalCount),
    )
  }

  enum Msg {
    case ForTable(msg: SearchResultsTable.Msg)
    case ForPagination(msg: PaginationControls.Msg)
  }
}
