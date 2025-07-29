package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.subs.{InstancesManager, WorkflowsManager}

import scala.scalajs.js.annotation.JSExportTopLevel

final case class Model(
    workflows: WorkflowsManager,
    instances: InstancesManager,
)

enum Msg {
  case NoOp
  case ForWorkflows(msg: WorkflowsManager.Msg)
  case ForInstances(msg: InstancesManager.Msg)
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    val (workflowsManager, workflowsCmd) = WorkflowsManager.initial(Msg.ForWorkflows.apply)
    (Model(workflowsManager, InstancesManager.initial), workflowsCmd)
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp =>
      (model, Cmd.None)

    case Msg.ForWorkflows(wmMsg) =>
      // The subsystem now returns the correct command type (Cmd[IO, Msg])
      // No more mapping or manual command batching is needed here.
      val (updatedWorkflowsManager, cmd) = model.workflows.update(wmMsg)
      val updatedModel                   = model.copy(workflows = updatedWorkflowsManager)
      (updatedModel, cmd)

    case Msg.ForInstances(imMsg) =>
      val (updatedInstancesManager, cmd) = model.instances.update(imMsg)
      // We still need to map the InstancesManager's internal messages.
      (model.copy(instances = updatedInstancesManager), cmd.map(Msg.ForInstances.apply))
  }

  def view(model: Model): Html[Msg] =
    div(
      ReusableViews.headerView,
      main(
        div(cls := "container")(
          div(cls := "columns")(
            model.workflows.view.map(Msg.ForWorkflows.apply),
            model.instances.view(model.workflows.selectedWorkflowId).map(Msg.ForInstances.apply),
          ),
        ),
      ),
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None
}
