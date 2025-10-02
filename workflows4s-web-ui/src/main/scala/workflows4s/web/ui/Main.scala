package workflows4s.web.ui

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.components.instance.InstancesManager
import workflows4s.web.ui.components.template.{WorkflowSelector, WorkflowsManager}
import workflows4s.web.ui.components.util.AsyncView
import workflows4s.web.ui.util.UIConfig

import scala.scalajs.js.annotation.*

final case class Model(
    workflows: WorkflowsManager,
    instances: Option[InstancesManager],
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
    val cmd: Cmd[IO, Msg]                = workflowsCmd.map(Msg.ForWorkflows(_)) |+| Cmd.Run(UIConfig.load.as(Msg.NoOp))
    (Model(workflowsManager, None), cmd)
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp        => (model, Cmd.None)

    case Msg.ForWorkflows(workflowsMsg) =>
      val newWorkflowDef           = workflowsMsg match {
        case WorkflowsManager.Msg.ForSelector(msg) =>
          msg match {
            case AsyncView.Msg.Propagate(msg) =>
              msg match {
                case WorkflowSelector.Msg.Select(workflowDef) => Some(workflowDef)
              }
            case _                            => None
          }
      }
      val newInstanceManager       = newWorkflowDef.map(wd => InstancesManager.initial(wd))
      val cmd1                     = newInstanceManager.map(_._2).getOrElse(Cmd.None).map(Msg.ForInstances(_))
      val (updatedWorkflows, cmd2) = model.workflows.update(workflowsMsg)
      (
        model.copy(workflows = updatedWorkflows, instances = newInstanceManager.map(_._1).orElse(model.instances)),
        Cmd.merge(cmd1, cmd2.map(Msg.ForWorkflows.apply)),
      )

    case Msg.ForInstances(instancesMsg) =>
      model.instances.map(_.update(instancesMsg)) match {
        case Some((updatedInstances, cmd)) => (model.copy(instances = updatedInstances.some), cmd.map(Msg.ForInstances.apply))
        case None                          => model -> Cmd.None
      }

    case Msg.FollowExternalLink(url) => model -> Nav.loadUrl(url)
  }

  def view(model: Model): Html[Msg] =
    div(
      headerView,
      section(cls := "section")(
        div(cls := "container is-fluid")(
          div(cls := "columns")(
            model.workflows.view.map(Msg.ForWorkflows.apply),
            div(cls := "column")(
              div(cls := "box")(
                model.instances match {
                  case Some(instMngr) => instMngr.view.map(Msg.ForInstances.apply)
                  case None           =>
                    div(cls := "has-text-centered p-6")(
                      p("Select a workflow from the menu to get started."),
                    )
                },
              ),
            ),
          ),
        ),
      ),
      footerView,
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None

  private def headerView: Html[Nothing] =
    nav(cls := "navbar has-shadow")(
      div(cls := "navbar-brand")(
        a(cls := "navbar-item", href := "/")(
          h1(cls := "title is-4")("Workflows4s Web UI"),
        ),
      ),
    )

  private def footerView: Html[Msg] =
    footer(cls := "footer mt-6")(
      div(cls := "content has-text-centered")(
        p(
          text("Built with "),
          a(href := "https://business4s.org/workflows4s/")(strong("Workflows4s")),
          text(" - A lightweight workflow engine for Scala"),
        ),
      ),
    )
}
