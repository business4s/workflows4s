package workflows4s.web.ui

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import cats.effect.kernel.Async
import org.scalajs.dom
import tyrian.*
import tyrian.Html.*
import workflows4s.web.ui.components.template.{TemplateDetailsView, TemplateSelector, TemplatesList}
import workflows4s.web.ui.components.util.AsyncView
import workflows4s.web.ui.util.{MermaidSupport, UIConfig}

import scala.scalajs.js.annotation.*
import workflows4s.web.api.model.WorkflowDefinition
import workflows4s.web.ui.components.instance.MermaidWebComponent
import workflows4s.web.ui.components.template.InstancesFilterBar

enum WorkflowView {
  case Definition
  case Instances(queryParams: Map[String, String])
  case Instance(instanceId: String)
}

enum Route {
  case Home
  case Workflow(workflowId: String, view: WorkflowView)
}

object Route {
  private def currentBasePath: String = UIConfig.detectBasePathFromLocation(dom.window.location.pathname)

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
    val (path, query) = url.split('?') match {
      case Array(p, q) => (p, q)
      case Array(p)    => (p, "")
      case _           => (url, "")
    }
    val noHash        = path.takeWhile(_ != '#')
    val parts         = noHash.split('/').toList.filter(_.nonEmpty)

    def decode(s: String): String = scalajs.js.URIUtils.decodeURIComponent(s)

    parts match {
      case Nil                                                           => Route.Home
      case "workflows" :: workflowId :: "definition" :: Nil              => Route.Workflow(decode(workflowId), WorkflowView.Definition)
      case "workflows" :: workflowId :: "instances" :: instanceId :: Nil =>
        Route.Workflow(decode(workflowId), WorkflowView.Instance(decode(instanceId)))
      case "workflows" :: workflowId :: "instances" :: Nil               =>
        val params = parseQuery(query)
        Route.Workflow(decode(workflowId), WorkflowView.Instances(params))
      case "workflows" :: workflowId :: Nil                              => Route.Workflow(decode(workflowId), WorkflowView.Definition)
      case _                                                             => Route.Home
    }
  }

  def toInternalUrl(route: Route): String = {
    def encode(s: String): String = scalajs.js.URIUtils.encodeURIComponent(s)

    route match {
      case Route.Home                                                    => "/"
      case Route.Workflow(workflowId, WorkflowView.Definition)           => s"/workflows/${encode(workflowId)}/definition"
      case Route.Workflow(workflowId, WorkflowView.Instances(params))    =>
        val queryString =
          if params.isEmpty then ""
          else "?" + new dom.URLSearchParams(scalajs.js.Dictionary(params.toSeq*)).toString
        s"/workflows/${encode(workflowId)}/instances$queryString"
      case Route.Workflow(workflowId, WorkflowView.Instance(instanceId)) => s"/workflows/${encode(workflowId)}/instances/${encode(instanceId)}"
    }
  }

  private def parseQuery(query: String): Map[String, String] = {
    if query.isEmpty then Map.empty
    else {
      val params = new dom.URLSearchParams(query)
      val result = scala.collection.mutable.Map[String, String]()
      params.forEach { (value, key) => result += (key -> value) }
      result.toMap
    }
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
      val selectionChange: Option[Option[WorkflowDefinition]] = workflowsMsg match {
        case TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.Select(wd)))     => Some(Some(wd))
        case TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.ClearSelection)) => Some(None)
        case _                                                                                           => None
      }

      val newInstanceManager = selectionChange match {
        case Some(Some(wd)) => Some(TemplateDetailsView.initial(wd))
        case Some(None)     => None
        case None           => None
      }

      val (updatedWorkflows, cmd2) = model.workflows.update(workflowsMsg)

      val newRoute = selectionChange match {
        case Some(Some(wd)) =>
          model.route match {
            case Route.Workflow(wfId, view) if wfId == wd.id => Route.Workflow(wfId, view)
            case _                                           => Route.Workflow(wd.id, WorkflowView.Definition)
          }
        case Some(None)     => Route.Home
        case None           => model.route
      }

      val urlCmd =
        if selectionChange.isDefined && newRoute != model.route then Nav.pushUrl[IO](Route.toBrowserUrl(newRoute))
        else Cmd.None

      val updatedModel = model.copy(
        workflows = updatedWorkflows,
        instances = newInstanceManager.map(_._1).orElse(if selectionChange.isDefined then None else model.instances),
        route = newRoute,
        pendingRoute = if selectionChange.isDefined then None else model.pendingRoute,
      )

      val (finalModel, pendingCmd) = applyPendingRouteIfPossible(updatedModel)
      val cmd1                     = newInstanceManager.map(_._2).getOrElse(Cmd.None).map(Msg.ForInstances(_))

      (
        finalModel,
        cmd1 |+| cmd2.map(Msg.ForWorkflows.apply) |+| urlCmd |+| pendingCmd,
      )

    case Msg.ForInstances(instancesMsg) =>
      model.instances.map(_.update(instancesMsg)) match {
        case Some((updatedInstances, cmd)) =>
          val newRoute = updatedInstances.selectedTab match {
            case TemplateDetailsView.Tab.Definition      => Route.Workflow(updatedInstances.definition.id, WorkflowView.Definition)
            case TemplateDetailsView.Tab.Instances       =>
              Route.Workflow(updatedInstances.definition.id, WorkflowView.Instances(updatedInstances.filterBar.toQueryParams))
            case TemplateDetailsView.Tab.InstanceDetails =>
              updatedInstances.instanceView match {
                case Some(iv) => Route.Workflow(updatedInstances.definition.id, WorkflowView.Instance(iv.instanceId))
                case None     => Route.Workflow(updatedInstances.definition.id, WorkflowView.Instances(updatedInstances.filterBar.toQueryParams))
              }
          }

          val urlCmd = if newRoute != model.route then Nav.pushUrl(Route.toBrowserUrl(newRoute))(using summon[Async[IO]]) else Cmd.None

          val updatedModel = model.copy(
            instances = updatedInstances.some,
            route = newRoute,
          )
          (updatedModel, cmd.map(Msg.ForInstances.apply) |+| urlCmd)
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

    def findDefinition(workflowId: String): Option[WorkflowDefinition] =
      model.workflows.state.state match {
        case AsyncView.State.Ready(selector) => selector.defs.find(_.id == workflowId)
        case _                               => None
      }

    def definitionsLoaded: Boolean =
      model.workflows.state.state match {
        case AsyncView.State.Ready(_) => true
        case _                        => false
      }

    route match {
      case Route.Home =>
        clearSelectionIfPossible(model)

      case Route.Workflow(workflowId, view) =>
        findDefinition(workflowId) match {
          case Some(definition) =>
            val (baseInstances, cmd1) = TemplateDetailsView.initial(definition)

            val (finalInstances, cmd2) = view match {
              case WorkflowView.Definition           =>
                (baseInstances.copy(selectedTab = TemplateDetailsView.Tab.Definition), Cmd.None)
              case WorkflowView.Instances(params)    =>
                val filterBar   = InstancesFilterBar.fromQueryParams(params)
                val withFilters = baseInstances.copy(filterBar = filterBar, selectedTab = TemplateDetailsView.Tab.Instances)
                withFilters.update(TemplateDetailsView.Msg.ReloadInstances)
              case WorkflowView.Instance(instanceId) =>
                val withTab = baseInstances.copy(selectedTab = TemplateDetailsView.Tab.InstanceDetails)
                withTab.update(TemplateDetailsView.Msg.LoadInstance(instanceId))
            }

            val (updatedWorkflows, cmd3) = model.workflows.update(
              TemplatesList.Msg.ForSelector(AsyncView.Msg.Propagate(TemplateSelector.Msg.Select(definition))),
            )
            (
              model.copy(
                workflows = updatedWorkflows,
                instances = finalInstances.some,
                route = Route.Workflow(workflowId, view),
                pendingRoute = None,
              ),
              cmd1.map(Msg.ForInstances.apply) |+| cmd2.map(Msg.ForInstances.apply) |+| cmd3.map(Msg.ForWorkflows.apply),
            )
          case None             =>
            if definitionsLoaded then
            // Workflow doesn't exist, go to Home and don't retry
            (model.copy(instances = None, route = Route.Home, pendingRoute = None), Cmd.None)
            else
              // Definitions not loaded yet, store as pending route to apply later
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
