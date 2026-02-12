package workflows4s.web.ui.components.template

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.{WorkflowDefinition, WorkflowSearchRequest, WorkflowSearchResponse}
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.instance.InstanceView
import workflows4s.web.ui.components.template.TemplateDetailsView.Msg
import workflows4s.web.ui.components.util.AsyncView

import java.util.UUID

final case class TemplateDetailsView(
    definition: WorkflowDefinition,
    instanceIdInput: String,
    instanceView: Option[InstanceView],
    definitionDetails: DefinitionView,
    instancesTableView: AsyncView.For[InstancesTableWithPagination],
    filterBar: InstancesFilterBar,
    currentPage: Int,
    selectedTab: TemplateDetailsView.Tab,
    isSearchEnabled: Boolean,
) {

  def update(msg: TemplateDetailsView.Msg): (TemplateDetailsView, Cmd[IO, TemplateDetailsView.Msg]) = msg match {
    case TemplateDetailsView.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case TemplateDetailsView.Msg.LoadInstance(instanceId) =>
      val (view, cmd) = InstanceView.initial(definition.id, instanceId)
      this.copy(instanceView = view.some, selectedTab = TemplateDetailsView.Tab.InstanceDetails) -> cmd.map(TemplateDetailsView.Msg.ForInstance(_))

    case TemplateDetailsView.Msg.InstanceSelected(instanceId) =>
      this.copy(instanceIdInput = instanceId, selectedTab = TemplateDetailsView.Tab.InstanceDetails) -> Cmd.emit(Msg.LoadInstance(instanceId))

    case TemplateDetailsView.Msg.ForInstTableView(msg) =>
      msg match {
        case AsyncView.Msg.Propagate(InstancesTableWithPagination.Msg.ForPagination(PaginationControls.Msg.GoToPage(page))) =>
          // Intercept pagination to reload with new page
          this.copy(currentPage = page) -> Cmd.emit(Msg.ReloadInstances)
        case AsyncView.Msg.Propagate(InstancesTableWithPagination.Msg.ForTable(SearchResultsTable.Msg.RowClicked(id)))      =>
          // Intercept row click to load instance
          this -> Cmd.emit(Msg.InstanceSelected(id))
        case _                                                                                                              =>
          val (asyncView, asyncCmd) = instancesTableView.update(msg)
          this.copy(instancesTableView = asyncView) -> asyncCmd.map(Msg.ForInstTableView(_))
      }

    case TemplateDetailsView.Msg.ForDefinition(msg) =>
      val (newState, cmd) = definitionDetails.update(msg)
      this.copy(definitionDetails = newState) -> cmd.map(TemplateDetailsView.Msg.ForDefinition(_))

    case TemplateDetailsView.Msg.RefreshInstances =>
      this -> instancesTableView.refresh.map(Msg.ForInstTableView(_))

    case TemplateDetailsView.Msg.ReloadInstances =>
      val searchRequest        = TemplateDetailsView.buildSearchRequest(definition.id, filterBar, this.currentPage)
      val (newTable, tableCmd) = AsyncView.empty_(
        Http.searchWorkflows(searchRequest),
        response => InstancesTableWithPagination.fromResponse(response, filterBar.pageSize, this.currentPage),
      )
      this.copy(instancesTableView = newTable) -> tableCmd.map(Msg.ForInstTableView(_))

    case TemplateDetailsView.Msg.ForFilterBar(msg) =>
      val (newFilterBar, cmd) = filterBar.update(msg)
      val updated             = this.copy(filterBar = newFilterBar, currentPage = 0)
      updated -> Cmd.merge(cmd.map(Msg.ForFilterBar(_)), Cmd.emit(TemplateDetailsView.Msg.ReloadInstances))

    case TemplateDetailsView.Msg.ForInstance(subMsg) =>
      instanceView match {
        case Some(view) =>
          val (newView, cmd) = view.update(subMsg)
          this.copy(instanceView = newView.some) -> cmd.map(TemplateDetailsView.Msg.ForInstance(_))
        case None       => this -> Cmd.None
      }

    case TemplateDetailsView.Msg.TabSelected(tab) =>
      this.copy(selectedTab = tab) -> Cmd.None
  }

  def view: Html[TemplateDetailsView.Msg] =
    div(
      div(cls := "tabs")(
        ul(
          li(cls := s"${if selectedTab == TemplateDetailsView.Tab.Definition then "is-active" else ""}")(
            a(onClick(TemplateDetailsView.Msg.TabSelected(TemplateDetailsView.Tab.Definition)))("Definition"),
          ),
          li(cls := s"${if selectedTab == TemplateDetailsView.Tab.Instances then "is-active" else ""}")(
            a(onClick(TemplateDetailsView.Msg.TabSelected(TemplateDetailsView.Tab.Instances)))("Instances"),
          ),
          li(cls := s"${if selectedTab == TemplateDetailsView.Tab.InstanceDetails then "is-active" else ""}")(
            a(onClick(TemplateDetailsView.Msg.TabSelected(TemplateDetailsView.Tab.InstanceDetails)))("Instance details"),
          ),
        ),
      ),
      selectedTab match {
        case TemplateDetailsView.Tab.Instances       =>
          if isSearchEnabled then div(
            div(cls := "level mb-2")(
              div(cls := "level-left")(),
              div(cls := "level-right")(
                button(
                  cls := s"button is-small is-info ${if instancesTableView.isLoading then "is-loading" else ""}",
                  onClick(Msg.RefreshInstances),
                  disabled(instancesTableView.isLoading),
                )("Refresh"),
              ),
            ),
            filterBar.view.map(Msg.ForFilterBar(_)),
            instancesTableView.view.map(Msg.ForInstTableView(_)),
          )
          else
            div(cls := "notification has-text-centered")(
              p("Search functionality is not available because the server does not provide a WorkflowSearch implementation."),
            )
        case TemplateDetailsView.Tab.InstanceDetails =>
          div(
            instanceInputView,
            instanceView match {
              case Some(value) => value.view.map(TemplateDetailsView.Msg.ForInstance(_))
              case None        => div()
            },
          )
        case TemplateDetailsView.Tab.Definition      =>
          div(
            definitionDetails.view.map(TemplateDetailsView.Msg.ForDefinition(_)),
          )
      },
    )

  private def instanceInputView: Html[TemplateDetailsView.Msg] =
    div(
      div(cls := "field is-grouped")(
        div(cls := "control is-expanded")(
          label(cls := "label")("Instance ID"),
          input(
            cls         := "input",
            placeholder := "Enter instance ID (e.g., inst-1)",
            value       := instanceIdInput,
            onInput(TemplateDetailsView.Msg.InstanceIdChanged(_)),
          ),
        ),
      ),
      div(cls := "field is-grouped mt-2")(
        div(cls := "control")(
          button(
            cls := s"button is-primary",
            onClick(TemplateDetailsView.Msg.LoadInstance(instanceIdInput)),
          )("Load"),
        ),
        div(cls := "control")(
          button(
            cls := s"button is-info is-outlined",
            onClick(TemplateDetailsView.Msg.InstanceIdChanged(s"test-instance-${UUID.randomUUID()}")),
          )("Create Test Instance"),
        ),
      ),
    )

}

object TemplateDetailsView {
  def initial(definition: WorkflowDefinition, searchEnabled: Boolean): (TemplateDetailsView, Cmd[IO, Msg]) = {
    val filterBar              = InstancesFilterBar.default
    val searchRequest          = buildSearchRequest(definition.id, filterBar, 0)
    val (instancesTable, cmd1) = AsyncView.empty_(
      if searchEnabled then Http.searchWorkflows(searchRequest) else IO.pure(WorkflowSearchResponse.empty),
      response => InstancesTableWithPagination.fromResponse(response, filterBar.pageSize, 0),
    )
    val (definitionView, cmd2) = DefinitionView.initial(definition)
    TemplateDetailsView(
      definition = definition,
      instanceIdInput = "",
      instanceView = None,
      definitionDetails = definitionView,
      instancesTableView = instancesTable,
      filterBar = filterBar,
      currentPage = 0,
      selectedTab = Tab.Definition,
      isSearchEnabled = searchEnabled,
    ) -> Cmd.merge(cmd1.map(Msg.ForInstTableView(_)), cmd2.map(Msg.ForDefinition(_)))
  }

  private def buildSearchRequest(templateId: String, filterBar: InstancesFilterBar, page: Int): WorkflowSearchRequest = {
    WorkflowSearchRequest(
      templateId = templateId,
      status = filterBar.statusFilters,
      createdAfter = None,
      createdBefore = None,
      updatedAfter = None,
      updatedBefore = None,
      wakeupBefore = None,
      wakeupAfter = None,
      sort = filterBar.sortBy,
      limit = Some(filterBar.pageSize),
      offset = Some(page * filterBar.pageSize),
    )
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(instanceId: String)
    case ForInstance(msg: InstanceView.Msg)
    case ForDefinition(msg: DefinitionView.Msg)
    case ForInstTableView(msg: AsyncView.Msg[InstancesTableWithPagination])
    case ForFilterBar(msg: InstancesFilterBar.Msg)
    case RefreshInstances
    case ReloadInstances
    case TabSelected(tab: Tab)
    case InstanceSelected(instanceId: String)
  }

  enum Tab {
    case InstanceDetails, Definition, Instances
  }

}
