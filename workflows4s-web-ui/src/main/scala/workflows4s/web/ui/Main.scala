package workflows4s.web.ui

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import cats.effect.kernel.Async
import org.scalajs.dom
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.components.instance.MermaidWebComponent
import workflows4s.web.ui.components.template.{TemplateDetailsView, TemplateSelector, TemplatesList}
import workflows4s.web.ui.components.util.AsyncView
import workflows4s.web.ui.util.{MermaidSupport, UIConfig}

import scala.scalajs.js.annotation.*

enum Route {
  case Home
  case Workflow(workflowId: String)
  case Instance(workflowId: String, instanceId: String)
}

object Route {
  private def detectBasePathFromLocation(pathname: String): String =
    if pathname == "/ui" || pathname.startsWith("/ui/") then "/ui" else ""

  private def currentBasePath: String = detectBasePathFromLocation(dom.window.location.pathname)

  def normalizeInternalUrl(url: String): String = {
    val urlOrPath =
      if url.startsWith("http://") || url.startsWith("https://") then {
        val u = new dom.URL(url)
        u.pathname + u.search + u.hash
      } else url

    val base = currentBasePath
    if base.nonEmpty && urlOrPath.startsWith(base) then {
      val stripped = urlOrPath.drop(base.length)
      if stripped.isEmpty then "/" else stripped
    } else urlOrPath
  }

  def toBrowserUrl(route: Route): String = {
    val base     = currentBasePath
    val basePath = if base.endsWith("/") then base.dropRight(1) else base
    basePath + toInternalUrl(route)
  }

  def fromInternalUrl(url: String): Route = {
    val noHash  = url.takeWhile(_ != '#')
    val noQuery = noHash.takeWhile(_ != '?')
    val parts   = noQuery.split('/').toList.filter(_.nonEmpty)

    parts match {
      case Nil                                          => Route.Home
      case "workflows" :: workflowId :: Nil            => Route.Workflow(workflowId)
      case "workflows" :: workflowId :: "instances" :: instanceId :: Nil => Route.Instance(workflowId, instanceId)
      case _                                            => Route.Home
    }
  }

  def toInternalUrl(route: Route): String = route match {
    case Route.Home                               => "/"
    case Route.Workflow(workflowId)               => s"/workflows/$workflowId"
    case Route.Instance(workflowId, instanceId)   => s"/workflows/$workflowId/instances/$instanceId"
  }
}

final case class Model(
    workflows: TemplatesList,
    instances: Option[TemplateDetailsView],
    route: Route,
    /** Stores routes that can't be applied yet (e.g., when navigating to a workflow URL before definitions are loaded). */
    pendingRoute: Option[Route],
)

enum Msg {
  case NoOp
  case ForWorkflows(msg: TemplatesList.Msg)
  case ForInstances(msg: TemplateDetailsView.Msg)
  case FollowExternalLink(str: String)
  case RouteChanged(url: String)
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.basic(Msg.RouteChanged.apply, Msg.FollowExternalLink(_))

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    val (workflowsManager, workflowsCmd) = TemplatesList.initial
    val cmd: Cmd[IO, Msg]                = workflowsCmd.map(Msg.ForWorkflows(_)) |+|
      Cmd.Run(UIConfig.load.as(Msg.NoOp)) |+|
      Cmd.Run(MermaidWebComponent.register().as(Msg.NoOp)) |+|
      Cmd.Run(MermaidSupport.initialize.as(Msg.NoOp))

    val initialUrl   = dom.window.location.pathname + dom.window.location.search + dom.window.location.hash
    val initialRoute = Route.fromInternalUrl(Route.normalizeInternalUrl(initialUrl))

    val (model, routeCmd) = applyRoute(Model(workflowsManager, None, Route.Home, None), initialRoute)
    (model, Cmd.merge(cmd, routeCmd))
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp => (model, Cmd.None)

    case Msg.RouteChanged(url) =>
      val route = Route.fromInternalUrl(Route.normalizeInternalUrl(url))
      applyRoute(model, route)

    case Msg.ForWorkflows(workflowsMsg) =>
      val newWorkflowDef           = workflowsMsg match {
        case TemplatesList.Msg.ForSelector(msg) =>
          msg match {
            case AsyncView.Msg.Propagate(msg) =>
              msg match {
                case TemplateSelector.Msg.Select(workflowDef) => Some(workflowDef)
                case TemplateSelector.Msg.ClearSelection       => None
              }
            case _                            => None
          }
      }
      val newInstanceManager       = newWorkflowDef.map(wd => TemplateDetailsView.initial(wd))
      val cmd1                     = newInstanceManager.map(_._2).getOrElse(Cmd.None).map(Msg.ForInstances(_))
      val (updatedWorkflows, cmd2) = model.workflows.update(workflowsMsg)
      val urlCmd                   = newWorkflowDef match {
        case Some(wd) =>
          model.route match {
            case Route.Workflow(wfId) if wfId == wd.id       => Cmd.None
            case Route.Instance(wfId, _) if wfId == wd.id    => Cmd.None
            case _                                            => Nav.pushUrl(Route.toBrowserUrl(Route.Workflow(wd.id)))(using summon[Async[IO]])
          }
        case None => Cmd.None
      }

      val updatedModel             = model.copy(
        workflows = updatedWorkflows,
        instances = newInstanceManager.map(_._1).orElse(model.instances),
        route = newWorkflowDef.map(wd => Route.Workflow(wd.id)).getOrElse(model.route),
        pendingRoute = if newWorkflowDef.isDefined then None else model.pendingRoute,
      )

      val (finalModel, pendingCmd) = applyPendingRouteIfPossible(updatedModel)

      (
        finalModel,
        cmd1 |+| cmd2.map(Msg.ForWorkflows.apply) |+| urlCmd |+| pendingCmd,
      )

    case Msg.ForInstances(instancesMsg) =>
      val urlCmd = instancesMsg match {
        case TemplateDetailsView.Msg.LoadInstance(instanceId) =>
          model.instances match {
            case Some(view) if model.route != Route.Instance(view.definition.id, instanceId) =>
              Nav.pushUrl(Route.toBrowserUrl(Route.Instance(view.definition.id, instanceId)))(using summon[Async[IO]])
            case _ => Cmd.None
          }
        case _                                               => Cmd.None
      }

      model.instances.map(_.update(instancesMsg)) match {
        case Some((updatedInstances, cmd)) =>
          val updatedModel = model.copy(
            instances = updatedInstances.some,
            route = instancesMsg match {
              case TemplateDetailsView.Msg.LoadInstance(instanceId) => Route.Instance(updatedInstances.definition.id, instanceId)
              case _                                               => model.route
            },
          )
          (updatedModel, Cmd.merge(cmd.map(Msg.ForInstances.apply), urlCmd))
        case None                          => model -> Cmd.None
      }

    case Msg.FollowExternalLink(url) => model -> Nav.loadUrl(url)(using summon[Async[IO]])
  }

  private def applyPendingRouteIfPossible(model: Model): (Model, Cmd[IO, Msg]) =
    model.pendingRoute match {
      case Some(route) =>
        val (m, cmd) = applyRoute(model.copy(pendingRoute = None), route)
        (m, cmd)
      case None        => (model, Cmd.None)
    }

  private def applyRoute(model: Model, route: Route): (Model, Cmd[IO, Msg]) = {
    def clearSelectionIfPossible(m: Model): (Model, Cmd[IO, Msg]) = {
      val (updatedWorkflows, cmd) = m.workflows.update(
        TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.ClearSelection)),
      )
      (m.copy(workflows = updatedWorkflows, instances = None, route = Route.Home, pendingRoute = None), cmd.map(Msg.ForWorkflows.apply))
    }

    def findDefinition(workflowId: String) =
      model.workflows.state.state match {
        case AsyncView.State.Ready(selector) => selector.defs.find(_.id == workflowId)
        case _                               => None
      }

    route match {
      case Route.Home =>
        clearSelectionIfPossible(model)

      case Route.Workflow(workflowId) =>
        findDefinition(workflowId) match {
          case Some(definition) =>
            val (newInstances, cmd1) = TemplateDetailsView.initial(definition)
            val (updatedWorkflows, cmd2) = model.workflows.update(
              TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.Select(definition))),
            )
            (
              model.copy(
                workflows = updatedWorkflows,
                instances = newInstances.some,
                route = Route.Workflow(workflowId),
                pendingRoute = None,
              ),
              Cmd.merge(cmd1.map(Msg.ForInstances.apply), cmd2.map(Msg.ForWorkflows.apply)),
            )
          case None             =>
            (model.copy(instances = None, route = route, pendingRoute = route.some), Cmd.None)
        }

      case Route.Instance(workflowId, instanceId) =>
        findDefinition(workflowId) match {
          case Some(definition) =>
            val (baseInstances, cmd1) = TemplateDetailsView.initial(definition)
            val (withInstance, cmd2)  = baseInstances.update(TemplateDetailsView.Msg.InstanceIdChanged(instanceId))
            val (finalInstances, cmd3) = withInstance.update(TemplateDetailsView.Msg.LoadInstance(instanceId))
            val (updatedWorkflows, cmd4) = model.workflows.update(
              TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.Select(definition))),
            )
            (
              model.copy(
                workflows = updatedWorkflows,
                instances = finalInstances.some,
                route = Route.Instance(workflowId, instanceId),
                pendingRoute = None,
              ),
              Cmd.merge(
                Cmd.merge(cmd1.map(Msg.ForInstances.apply), cmd2.map(Msg.ForInstances.apply)),
                Cmd.merge(cmd3.map(Msg.ForInstances.apply), cmd4.map(Msg.ForWorkflows.apply)),
              ),
            )
          case None             =>
            (model.copy(instances = None, route = route, pendingRoute = route.some), Cmd.None)
        }
    }
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
        a(cls := "navbar-item", href := Route.toBrowserUrl(Route.Home))(
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
