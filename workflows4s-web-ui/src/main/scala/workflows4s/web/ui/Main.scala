package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.subs.{InstancesManager, WorkflowsManager}

import scala.scalajs.js.annotation.JSExportTopLevel

// MAIN MODEL - Updated to use the new subsystem classes
final case class Model(
    workflows: WorkflowsManager,
    instances: InstancesManager,
)

// MAIN MSG - Using enum as Dave suggested
enum Msg {
  case NoOp
  case ForWorkflows(msg: WorkflowsManager.Msg)
  case ForInstances(msg: InstancesManager.Msg)
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(Msg.NoOp)  

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    // Use the new initial methods with message mapping
    val (workflowsManager, workflowsCmd) = WorkflowsManager.initial(Msg.ForWorkflows.apply)
    val (instancesManager, _) = InstancesManager.initial
    
    (Model(workflowsManager, instancesManager), workflowsCmd)
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp => (model, Cmd.None)

    case Msg.ForWorkflows(wmMsg) =>
      val (updatedWorkflowsManager, cmd) = model.workflows.update(wmMsg)
      val newModel = model.copy(workflows = updatedWorkflowsManager)

      // When a new workflow is selected, reset the instance manager
      val instanceResetCmd = wmMsg match {
        case WorkflowsManager.Msg.Select(_) => Cmd.emit(Msg.ForInstances(InstancesManager.Msg.Reset))
        case _                              => Cmd.None
      }

      (newModel, Cmd.Batch(cmd.map(Msg.ForWorkflows.apply), instanceResetCmd))

    case Msg.ForInstances(imMsg) =>
      val (updatedInstancesManager, cmd) = model.instances.update(imMsg)
      (model.copy(instances = updatedInstancesManager), cmd.map(Msg.ForInstances.apply))
  }

  def view(model: Model): Html[Msg] =
    div(
      ReusableViews.headerView,
      main(
        div(cls := "container")(
          div(cls := "columns")(
            // Use the new view methods directly on the subsystems
            model.workflows.view.map(Msg.ForWorkflows.apply),
            model.instances.view(model.workflows.selectedWorkflowId).map(Msg.ForInstances.apply),
          ),
        ),
      ),
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None
  // TODO: Add subscriptions (timers, websockets, etc.) if needed in the future
}