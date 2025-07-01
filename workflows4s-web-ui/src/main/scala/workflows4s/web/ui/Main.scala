package workflows4s.web.ui

import cats.effect.IO
import sttp.client4.*
import sttp.client4.circe.*
import sttp.client4.impl.cats.FetchCatsBackend
import tyrian.Html.*
import tyrian.*
import workflows4s.web.ui.components.*
import workflows4s.web.ui.models.*

import scala.scalajs.js.annotation.JSExportTopLevel


@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (Model.initial, Cmd.emit(Msg.LoadWorkflows))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = {
    case Msg.NoOp => (model, Cmd.None)

    case Msg.LoadWorkflows =>
      (model.loadingWorkflows, Http.loadWorkflows)

    case Msg.WorkflowsLoadedSuccess(workflows) =>
      (model.workflowsReady(workflows), Cmd.None)

    case Msg.WorkflowsLoadedFailure(reason) =>
      (model.workflowsFailed(reason), Cmd.None)

    case Msg.WorkflowSelected(workflowId) =>
      (model.withSelectedWorkflow(Some(workflowId)), Cmd.None)

    case Msg.InstanceIdChanged(text) =>
      (model.withInstanceIdInput(text), Cmd.None)

    case Msg.LoadInstance =>
      model.selectedWorkflowId match {
        case Some(wfId) if model.instanceIdInput.nonEmpty =>
          (model.loadingInstance, Http.loadInstance(wfId, model.instanceIdInput))
        case _ =>
          (model.withInstanceError("Select a workflow and provide an instance ID."), Cmd.None)
      }

    case Msg.InstanceLoadedSuccess(instance) =>
      (model.withInstance(instance), Cmd.None)

    case Msg.InstanceLoadedFailure(reason) =>
      (model.withInstanceError(reason), Cmd.None)

    case Msg.ToggleJsonState =>
      (model.toggleJsonState, Cmd.None)
  }

  def view(model: Model): Html[Msg] =
    div(
      ReusableViews.headerView,
      main(
        div(cls := "container")(
          model.appState match {
            case AppState.Initializing | AppState.LoadingWorkflows =>
              section(cls := "section is-medium has-text-centered")(
                p(cls := "title is-4")("Fetching workflow definitions..."),
              )
            case _ =>
              div(cls := "columns")(
                sidebarView(model),
                mainContentView(model),
              )
          },
        ),
      ),
    )

  private def sidebarView(model: Model): Html[Msg] =
    aside(cls := "column is-one-quarter")(
      nav(cls := "menu p-4")(
        p(cls := "menu-label")("Available Workflows"),
        ul(cls := "menu-list")(
          model.workflows.map { wf =>
            li(
              a(
                cls     := (if (model.selectedWorkflowId.contains(wf.id)) "is-active" else ""),
                onClick(Msg.WorkflowSelected(wf.id)),
              )(wf.name),
            )
          },
        ),
      ),
    )
  private def mainContentView(model: Model): Html[Msg] =
    div(cls := "column")(
      model.selectedWorkflowId match {
        case None =>
          div(cls := "box has-text-centered p-6")(
            p("Select a workflow from the menu to get started."),
          )
        case Some(_) =>
          div(
            model.appState match {
              case AppState.LoadingInstance =>
                section(cls := "section is-medium has-text-centered")(
                  p(cls := "title is-4")("Fetching instance details..."),
                )
              case _ =>
                instanceView(model)
            },
          )
      },
    )


  // private def workflowsView(model: Model): Html[Msg] =
  //   section(cls := "section")(
  //     h2(cls := "title is-2")("Available Workflows"),
  //     div(cls := "columns is-multiline")(
  //       model.workflows.map(wf => WorkflowCard.view(wf, model.selectedWorkflowId.contains(wf.id))),
  //     ),
  //   )

  private def instanceView(model: Model): Html[Msg] =
    section(cls := "section")(
      h2(cls := "title is-2")("Workflow Instance"),
      div(cls := "box")(
        instanceInputView(model),
        model.instanceError.map(ReusableViews.errorView).getOrElse(div()),
        model.currentInstance.map(instanceDetailsView(model, _)).getOrElse(div()),
      ),
    )

  private def instanceInputView(model: Model): Html[Msg] =
    div(cls := "field is-grouped")(
      div(cls := "control is-expanded")(
        label(cls := "label")("Instance ID"),
        input(
          cls     := "input",
          placeholder := "Enter instance ID",
          value       := model.instanceIdInput,
          onInput(Msg.InstanceIdChanged(_)),
        ),
      ),
      div(cls := "control")(
        label(cls := "label")(" "), // Empty label for alignment
        button(
          cls    := s"button is-primary ${if (model.appState == AppState.LoadingInstance) "is-loading" else ""}",
          onClick(Msg.LoadInstance),
          disabled(model.appState == AppState.LoadingInstance),
        )("Load"),
      ),
    )

  private def instanceDetailsView(model: Model, instance: WorkflowInstance): Html[Msg] =
    div(cls := "content")(
      h3(s"Details for: ${instance.id}"),
      ReusableViews.instanceField("Definition", span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      button(
        cls     := "button is-info is-small mt-4",
        onClick(Msg.ToggleJsonState),
      )(if (model.showJsonState) "Hide State" else "Show State"),
      if (model.showJsonState) jsonStateViewer(instance) else div(),
    )

  private def jsonStateViewer(instance: WorkflowInstance): Html[Msg] =
    pre(
      code(instance.state.map(_.spaces2).getOrElse("No state available.")),
    )

  def subscriptions(model: Model): Sub[IO, Msg] = Sub.None
}

// HTTP calls in a separate object for clarity
object Http {
  private val backend = FetchCatsBackend[IO]()
  private val baseUri = uri"http://localhost:8081/api/v1"

  def loadWorkflows: Cmd[IO, Msg] = {
    val request = basicRequest
      .get(baseUri.addPath("definitions"))
      .response(asJson[List[WorkflowDefinition]])

    Cmd.Run(
      backend
        .send(request)
        .map(_.body)
        .map {
          case Right(workflows) => Msg.WorkflowsLoadedSuccess(workflows)
          case Left(error)      => Msg.WorkflowsLoadedFailure(s"Failed to decode: $error")
        }
        .handleError(err => Msg.WorkflowsLoadedFailure(err.getMessage)),
    )
  }

  def loadInstance(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
    val request = basicRequest
      .get(baseUri.addPath("definitions", workflowId, "instances", instanceId))
      .response(asJson[WorkflowInstance])

    Cmd.Run(
      backend
        .send(request)
        .map(_.body)
        .map {
          case Right(instance) => Msg.InstanceLoadedSuccess(instance)
          case Left(error)     => Msg.InstanceLoadedFailure(s"Failed to decode: $error")
        }
        .handleError(err => Msg.InstanceLoadedFailure(err.getMessage)),
    )
  }
}