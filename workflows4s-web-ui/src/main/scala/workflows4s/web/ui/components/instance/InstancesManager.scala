package workflows4s.web.ui.components.instance

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.Http
import workflows4s.web.ui.components.instance.InstancesManager.Msg
import workflows4s.web.ui.components.template.SearchResultsTable
import workflows4s.web.ui.components.util.AsyncView

import java.util.UUID

final case class InstancesManager(
    templateId: String,
    instanceIdInput: String,
    instanceDetails: Option[AsyncView.For[InstanceView]],
    instancesTable: AsyncView.For[SearchResultsTable],
    selectedTab: InstancesManager.Tab,
) {

  def update(msg: InstancesManager.Msg): (InstancesManager, Cmd[IO, InstancesManager.Msg]) = msg match {
    case InstancesManager.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case InstancesManager.Msg.LoadInstance(instanceId) =>
      val (asyncView, asyncCmd) = AsyncView.empty(Http.getInstance(templateId, instanceId), instance => InstanceView.initial(instance))
      this.copy(instanceDetails = asyncView.some) -> asyncCmd.map(InstancesManager.Msg.ForInstance(_))

    case InstancesManager.Msg.InstanceSelected(instanceId) =>
      this.copy(instanceIdInput = instanceId, selectedTab = InstancesManager.Tab.InstanceDetails) -> Cmd.emit(Msg.LoadInstance(instanceId))

    case InstancesManager.Msg.ForInstTable(msg) =>
      val (asyncView, asyncCmd) = instancesTable.update(msg)
      this.copy(instancesTable = asyncView) -> asyncCmd.map(InstancesManager.Msg.ForInstTable(_))

    case InstancesManager.Msg.RefreshInstances =>
      this -> instancesTable.refresh.map(InstancesManager.Msg.ForInstTable(_))

    case InstancesManager.Msg.ForInstance(subMsg) =>
      instanceDetails match {
        case Some(value) =>
          val (newState, cmd) = value.update(subMsg)
          this.copy(instanceDetails = newState.some) -> cmd.map(InstancesManager.Msg.ForInstance(_))
        case None        => this -> Cmd.None
      }

    case InstancesManager.Msg.TabSelected(tab) =>
      this.copy(selectedTab = tab) -> Cmd.None
  }

  def view: Html[InstancesManager.Msg] =
    div(
      div(cls := "tabs")(
        ul(
          li(cls := s"${if selectedTab == InstancesManager.Tab.InstanceDetails then "is-active" else ""}")(
            a(onClick(InstancesManager.Msg.TabSelected(InstancesManager.Tab.InstanceDetails)))("Instance details"),
          ),
          li(cls := s"${if selectedTab == InstancesManager.Tab.Definition then "is-active" else ""}")(
            a(onClick(InstancesManager.Msg.TabSelected(InstancesManager.Tab.Definition)))("Definition"),
          ),
          li(cls := s"${if selectedTab == InstancesManager.Tab.Instances then "is-active" else ""}")(
            a(onClick(InstancesManager.Msg.TabSelected(InstancesManager.Tab.Instances)))("Instances"),
          ),
        ),
      ),
      selectedTab match {
        case InstancesManager.Tab.Instances       =>
          div(
            div(cls := "control is-flex is-justify-content-flex-end")(
              button(
                cls := s"button is-small is-info ${if instancesTable.isLoading then "is-loading" else ""}",
                onClick(Msg.RefreshInstances),
                disabled(instancesTable.isLoading),
              )("Refresh"),
            ),
            instancesTable.view.map({
              case AsyncView.Msg.Propagate(SearchResultsTable.Msg.RowClicked(id)) => Msg.InstanceSelected(id)
              case x                                                              => Msg.ForInstTable(x)
            }),
          )
        case InstancesManager.Tab.InstanceDetails =>
          div(
            instanceInputView,
            instanceDetails match {
              case Some(value) => value.view.map(InstancesManager.Msg.ForInstance(_))
              case None        => div()
            },
          )
        case InstancesManager.Tab.Definition      =>
          div(
            h4("Definition"),
            p(s"Template ID: ${templateId}"),
          )
      },
    )

  private def instanceInputView: Html[InstancesManager.Msg] =
    div(
      div(cls := "field is-grouped")(
        div(cls := "control is-expanded")(
          label(cls := "label")("Instance ID"),
          input(
            cls         := "input",
            placeholder := "Enter instance ID (e.g., inst-1)",
            value       := instanceIdInput,
            onInput(InstancesManager.Msg.InstanceIdChanged(_)),
          ),
        ),
      ),
      div(cls := "field is-grouped mt-2")(
        div(cls := "control")(
          button(
            cls := s"button is-primary ${if instanceDetails.exists(_.isLoading) then "is-loading" else ""}",
            onClick(InstancesManager.Msg.LoadInstance(instanceIdInput)),
            disabled(instanceDetails.exists(_.isLoading)),
          )("Load"),
        ),
        div(cls := "control")(
          button(
            cls := s"button is-info is-outlined",
            onClick(InstancesManager.Msg.InstanceIdChanged(s"test-instance-${UUID.randomUUID()}")),
          )("Create Test Instance"),
        ),
      ),
    )

}

object InstancesManager {
  def initial(templateId: String): (InstancesManager, Cmd[IO, Msg]) = {
    val (instancesTable, cmd) = AsyncView.empty_(Http.searchWorkflows(templateId), results => SearchResultsTable(results))
    InstancesManager(
      templateId = templateId,
      instanceIdInput = "",
      instanceDetails = None,
      instancesTable = instancesTable,
      selectedTab = Tab.InstanceDetails,
    ) -> cmd.map(Msg.ForInstTable(_))
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(instanceId: String)
    case ForInstance(msg: AsyncView.Msg[InstanceView])
    case ForInstTable(msg: AsyncView.Msg[SearchResultsTable])
    case RefreshInstances
    case TabSelected(tab: Tab)
    case InstanceSelected(instanceId: String)
  }

  enum Tab {
    case InstanceDetails, Definition, Instances
  }

}
