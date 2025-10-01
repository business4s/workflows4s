package workflows4s.web.ui.components.template

import tyrian.Html
import tyrian.Html.*
import workflows4s.web.api.model.WorkflowSearchResult
import workflows4s.web.ui.components.util.Component

case class SearchResultsTable(results: List[WorkflowSearchResult]) extends Component.ReadOnly[SearchResultsTable] {


  override def view: Html[Msg] =
    table(cls := "table is-striped is-fullwidth is-hoverable")(
      thead(
        tr(
          th("Id"),
          th("Status"),
          th("Created"),
          th("Updated"),
        ),
      ),
      tbody(
        results.map { r =>
          tr(
            td(r.instanceId),
            td(r.status.toString),
            td(r.createdAt.toString),
            td(r.updatedAt.toString),
            td(r.wakeupAt.toString),
          )
        }
      )
    )
}
