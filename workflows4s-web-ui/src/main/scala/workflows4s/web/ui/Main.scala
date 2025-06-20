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
      style := """
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji";
        background-color: #f4f7f9;
        min-height: 100vh;
        color: #333;
      """
    )(
      ReusableViews.headerView,
      main(
        style := """
          max-width: 1200px;
          margin: 0 auto;
          padding: 0 2rem;
        """
      )(
        model.appState match {
          case AppState.Initializing | AppState.LoadingWorkflows =>
            ReusableViews.loadingSpinner("Fetching workflow definitions...")
          case AppState.Ready(maybeError) =>
            div(
              maybeError.map(ReusableViews.errorView).getOrElse(div()),
              workflowsView(model),
              instanceView(model),
            )
          case AppState.LoadingInstance =>
            div(
              workflowsView(model),
              ReusableViews.loadingSpinner("Fetching instance details..."),
            )
        },
      ),
    )

  private def workflowsView(model: Model): Html[Msg] =
    section(
      h2(style("color", "#495057"))("Available Workflows"),
      div(
        style := """
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
          gap: 1.5rem;
          margin-bottom: 2rem;
        """
      )(
        model.workflows.map(wf => WorkflowCard.view(wf, model.selectedWorkflowId.contains(wf.id))),
      ),
    )

  private def instanceView(model: Model): Html[Msg] =
    section(
      h2(style("color", "#495057"))("Workflow Instance"),
      div(
        style := """
          background: white;
          padding: 1.5rem 2rem;
          border-radius: 12px;
          box-shadow: 0 4px 15px rgba(0,0,0,0.05);
        """
      )(
        instanceInputView(model),
        model.instanceError.map(ReusableViews.errorView).getOrElse(div()),
        model.currentInstance.map(instanceDetailsView(model, _)).getOrElse(div()),
      ),
    )

  private def instanceInputView(model: Model): Html[Msg] =
    div(
      style := """
        display: flex;
        flex-wrap: wrap;
        gap: 1rem;
        align-items: flex-end;
        margin-bottom: 1rem;
      """
    )(
      div(style("flex-grow", "1"))(
        label(
          style := """
            display: block;
            margin-bottom: 0.5rem;
            font-weight: 500;
          """
        )("Instance ID"),
        input(
          placeholder := "Enter instance ID",
          value       := model.instanceIdInput,
          onInput(Msg.InstanceIdChanged(_)),
          style := """
            width: 100%;
            padding: 0.75rem;
            border-radius: 8px;
            border: 1px solid #ced4da;
            box-sizing: border-box;
          """
        ),
      ),
      button(
        onClick(Msg.LoadInstance),
        disabled(model.appState == AppState.LoadingInstance),
        style := """
          background: #667eea;
          color: white;
          border: none;
          padding: 0.75rem 1.5rem;
          border-radius: 8px;
          cursor: pointer;
          font-weight: 500;
          transition: background-color 0.2s;
        """
      )(if (model.appState == AppState.LoadingInstance) "Loading..." else "Load"),
    )

  private def instanceDetailsView(model: Model, instance: WorkflowInstance): Html[Msg] =
    div(
      style("margin-top", "1.5rem")
    )(
      h3(style("margin-top", "0"))(s"Details for: ${instance.id}"),
      ReusableViews.instanceField("Definition", span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      button(
        style := """
          margin-top: 1rem;
          background: #007bff;
          color: white;
          border: none;
          padding: 0.5rem 1rem;
          border-radius: 5px;
          cursor: pointer;
        """,
        onClick(Msg.ToggleJsonState),
      )(if (model.showJsonState) "Hide State" else "Show State"),
      if (model.showJsonState) jsonStateViewer(instance) else div(),
    )

  private def jsonStateViewer(instance: WorkflowInstance): Html[Msg] =
    pre(
      style := """
        background: #2d3748;
        color: #e2e8f0;
        padding: 1rem;
        border-radius: 8px;
        margin-top: 1rem;
        overflow-x: auto;
        font-family: monospace;
      """
    )(
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