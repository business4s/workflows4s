package workflows4s.web.ui.components.template

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.util.Component

case class PaginationControls(
    currentPage: Int,
    pageSize: Int,
    totalCount: Int,
) extends Component {

  override type Self = PaginationControls
  override type Msg  = PaginationControls.Msg

  override def update(msg: Msg): (PaginationControls, Cmd[IO, Msg]) = msg match {
    case PaginationControls.Msg.GoToPage(page) => this.copy(currentPage = page) -> Cmd.None
  }

  private def totalPages: Int      = math.ceil(totalCount.toDouble / pageSize).toInt
  private def isFirstPage: Boolean = currentPage == 0
  private def isLastPage: Boolean  = currentPage >= totalPages - 1
  private def startIndex: Int      = currentPage * pageSize + 1
  private def endIndex: Int        = math.min((currentPage + 1) * pageSize, totalCount)

  override def view: Html[Msg] = {
    if totalCount == 0 then {
      div(cls := "has-text-centered p-4")(
        p(cls := "has-text-grey")("No instances found"),
      )
    } else {
      nav(cls := "pagination is-centered mt-4", role := "navigation", attribute("aria-label", "pagination"))(
        button(
          cls := s"pagination-previous button ${if isFirstPage then "is-disabled" else ""}",
          onClick(PaginationControls.Msg.GoToPage(currentPage - 1)),
          disabled(isFirstPage),
        )("Previous"),
        button(
          cls := s"pagination-next button ${if isLastPage then "is-disabled" else ""}",
          onClick(PaginationControls.Msg.GoToPage(currentPage + 1)),
          disabled(isLastPage),
        )("Next"),
        ul(cls := "pagination-list")(
          li(
            span(cls := "pagination-link is-current")(
              text(s"Page ${currentPage + 1} of ${totalPages}"),
            ),
          ),
        ),
        div(cls := "has-text-centered mt-2")(
          p(cls := "is-size-7 has-text-grey")(s"Showing $startIndex-$endIndex of $totalCount instances"),
        ),
      )
    }
  }
}

object PaginationControls {
  enum Msg {
    case GoToPage(page: Int)
  }
}
