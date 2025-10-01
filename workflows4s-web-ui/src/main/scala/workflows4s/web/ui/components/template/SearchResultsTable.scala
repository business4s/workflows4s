package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.{Cmd, Html}
import tyrian.Html.*
import workflows4s.web.api.model.WorkflowSearchResult
import workflows4s.web.ui.components.util.Component

case class SearchResultsTable(results: List[WorkflowSearchResult]) extends Component {
  type Self = SearchResultsTable
  type Msg  = SearchResultsTable.Msg

  override def update(msg: Msg): (Self, Cmd[IO, Msg]) = this -> Cmd.None

  override def view: Html[Msg] =
    table(cls := "table is-striped is-fullwidth is-hoverable")(
      thead(
        tr(
          th("Id"),
          th("Status"),
          th("Created"),
          th("Updated"),
          th("Wakeup"),
        ),
      ),
      tbody()(
        results.map(r => {
          tr(onClick(SearchResultsTable.Msg.RowClicked(r.instanceId)))(
            td(r.instanceId),
            td(r.status.toString),
            td(r.createdAt.toString),
            td(r.updatedAt.toString),
            td(r.wakeupAt.map(_.toString).getOrElse("-")),
          )
        })*
      )
    )
}

object SearchResultsTable {
  enum Msg {
    case RowClicked(instanceId: String)
  }
}
