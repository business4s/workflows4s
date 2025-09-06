package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.subs.{InstancesManager, WorkflowsManager}

import scala.scalajs.js.annotation.*

final case class Model(
    workflows: WorkflowsManager,
    instances: InstancesManager,
)

enum Msg {
  case NoOp
  case ForWorkflows(msg: WorkflowsManager.Msg)
  case ForInstances(msg: InstancesManager.Msg)
  case FollowExternalLink(str: String)
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.basic(_ => Msg.NoOp, Msg.FollowExternalLink(_))

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    val (workflowsManager, workflowsCmd) = WorkflowsManager.initial
    (Model(workflowsManager, InstancesManager.initial), workflowsCmd.map(Msg.ForWorkflows(_)))
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp =>
      (model, Cmd.None)

    case Msg.ForWorkflows(workflowsMsg) =>
      val (updatedWorkflows, cmd) = model.workflows.update(workflowsMsg)
      (model.copy(workflows = updatedWorkflows), cmd.map(Msg.ForWorkflows.apply))

    case Msg.ForInstances(instancesMsg) =>
      val (updatedInstances, cmd) = model.instances.update(instancesMsg)
      (model.copy(instances = updatedInstances), cmd.map(Msg.ForInstances.apply))

    case Msg.FollowExternalLink(url) => model -> Nav.loadUrl(url)
  }

  def view(model: Model): Html[Msg] =
    div(
      ReusableViews.headerView,
      section(cls := "section")(
        div(cls := "container is-fluid")(
          div(cls := "columns")(
            model.workflows.view.map(Msg.ForWorkflows.apply),
            model.instances.view(model.workflows.selectedWorkflowId).map(Msg.ForInstances.apply),
          ),
        ),
      ),
      footerView,
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None

  private def footerView: Html[Msg] =
    footer(cls := "footer mt-6")(
      div(cls := "content has-text-centered")(
        p(
          text("Built with "),
          strong("Workflows4s"),
          text(" - A lightweight workflow engine for Scala"),
        ),
      ),
    )
}
