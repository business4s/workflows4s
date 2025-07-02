package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.subs.{InstancesManager, WorkflowsManager}

import scala.scalajs.js.annotation.JSExportTopLevel

// MAIN MODEL
final case class Model(
    workflows: WorkflowsManager.Model,
    instances: InstancesManager.Model,
)

object Model {
  def initial: Model = Model(WorkflowsManager.Model(), InstancesManager.Model())
}

// MAIN MSG 
sealed trait Msg
case object NoOp                                     extends Msg
case class ForWorkflows(msg: WorkflowsManager.Msg)   extends Msg
case class ForInstances(msg: InstancesManager.Msg)   extends Msg

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.initial, WorkflowsManager.Http.loadWorkflows.map(ForWorkflows.apply))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case NoOp => (model, Cmd.None)

    case ForWorkflows(wmMsg) =>
      val (updatedWorkflowsModel, cmd) = model.workflows.update(wmMsg)
      val newModel                     = model.copy(workflows = updatedWorkflowsModel)

      // When a new workflow is selected, reset the instance manager
      val instanceResetCmd = wmMsg match {
        case WorkflowsManager.Msg.Select(_) => Cmd.emit(ForInstances(InstancesManager.Msg.Reset))
        case _                              => Cmd.None
      }

      (newModel, Cmd.Batch(cmd.map(ForWorkflows.apply), instanceResetCmd))

    case ForInstances(imMsg) =>
      val (updatedInstancesModel, cmd) = model.instances.update(imMsg)
      (model.copy(instances = updatedInstancesModel), cmd.map(ForInstances.apply))
  }

  def view(model: Model): Html[Msg] =
    div(
      ReusableViews.headerView,
      main(
        div(cls := "container")(
          div(cls := "columns")(
            WorkflowsManager.view(model.workflows).map(ForWorkflows.apply),
            InstancesManager.view(model.instances, model.workflows.selectedWorkflowId).map(ForInstances.apply),
          ),
        ),
      ),
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None
  // TODO: Add subscriptions (timers, websockets, etc.) if needed in the future
}