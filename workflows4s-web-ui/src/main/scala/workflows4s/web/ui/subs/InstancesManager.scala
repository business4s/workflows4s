package workflows4s.web.ui.subs

import cats.effect.IO
import io.circe.Json
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.{ProgressResponse, WorkflowInstance}
import workflows4s.web.ui.components.{MermaidDiagramView, ReusableViews}
import workflows4s.web.ui.subs.InstancesManager.Msg

final case class InstancesManager(
    instanceIdInput: String,
    state: InstancesManager.State,
) {

  def update(msg: InstancesManager.Msg): (InstancesManager, Cmd[IO, InstancesManager.Msg]) = msg match {
    case InstancesManager.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case InstancesManager.Msg.LoadInstance(workflowId) =>
      val updated = this.copy(state = InstancesManager.State.Loading)
      val cmd     = InstancesManager.Http.loadInstance(workflowId, instanceIdInput)
      (updated, cmd)

    case InstancesManager.Msg.InstanceLoaded(result) =>
      result match {
        case Left(err)       => (this.copy(state = InstancesManager.State.Failed(err)), Cmd.None)
        case Right(instance) =>
          val updated = this.copy(state = InstancesManager.State.InstanceLoaded(instance, InstancesManager.ProgressView.Loading))
          val cmd     = InstancesManager.Http.loadProgress(instance.definitionId, instance.id)
          (updated, cmd)
      }

    case InstancesManager.Msg.ProgressLoaded(Right(progress)) =>
      state match {
        case InstancesManager.State.InstanceLoaded(instance, _) =>
          val progressView =
            InstancesManager.ProgressView.Loaded(progress, InstancesManager.StateDisplay.Hidden, InstancesManager.DiagramDisplay.Hidden)
          val updated      = this.copy(state = InstancesManager.State.InstanceLoaded(instance, progressView))
          (updated, Cmd.None)
        case _                                                  => (this, Cmd.None)
      }

    case InstancesManager.Msg.ProgressLoaded(Left(err)) =>
      state match {
        case InstancesManager.State.InstanceLoaded(instance, _) =>
          val progressView = InstancesManager.ProgressView.Failed(err)
          val updated      = this.copy(state = InstancesManager.State.InstanceLoaded(instance, progressView))
          (updated, Cmd.None)
        case _                                                  => (this, Cmd.None)
      }

    case InstancesManager.Msg.ToggleJsonState =>
      state match {
        case InstancesManager.State.InstanceLoaded(instance, InstancesManager.ProgressView.Loaded(progress, currentStateDisplay, diagramDisplay)) =>
          val newStateDisplay = currentStateDisplay match {
            case InstancesManager.StateDisplay.Hidden  => InstancesManager.StateDisplay.Visible
            case InstancesManager.StateDisplay.Visible => InstancesManager.StateDisplay.Hidden
          }
          val progressView    = InstancesManager.ProgressView.Loaded(progress, newStateDisplay, diagramDisplay)
          val updated         = this.copy(state = InstancesManager.State.InstanceLoaded(instance, progressView))
          (updated, Cmd.None)
        case _                                                                                                                                    => (this, Cmd.None)
      }

    case InstancesManager.Msg.ToggleMermaidViewer =>
      state match {
        case InstancesManager.State.InstanceLoaded(instance, InstancesManager.ProgressView.Loaded(progress, stateDisplay, currentDiagramDisplay)) =>
          val (newDiagramDisplay, cmd) = currentDiagramDisplay match {
            case InstancesManager.DiagramDisplay.Hidden =>
              val diagram = MermaidDiagramView(progress.mermaidCode, None)
              (InstancesManager.DiagramDisplay.Rendered(progress.mermaidUrl, diagram), Cmd.Emit(Msg.ForDiagram(MermaidDiagramView.Msg.Retry)))
            case _                                      =>
              (InstancesManager.DiagramDisplay.Hidden, Cmd.None)
          }
          val progressView             = InstancesManager.ProgressView.Loaded(progress, stateDisplay, newDiagramDisplay)
          val updated                  = this.copy(state = InstancesManager.State.InstanceLoaded(instance, progressView))
          (updated, cmd)
        case _                                                                                                                                    => (this, Cmd.None)
      }

    case InstancesManager.Msg.CreateTestInstance(workflowId) =>
      val updated = this.copy(state = InstancesManager.State.Loading)
      val cmd     = InstancesManager.Http.createTestInstance(workflowId)
      (updated, cmd)

    case InstancesManager.Msg.ForDiagram(subMsg) =>
      state match {
        case s @ InstancesManager.State
              .InstanceLoaded(_, p @ InstancesManager.ProgressView.Loaded(_, _, d @ InstancesManager.DiagramDisplay.Rendered(_, diagram))) =>
          val (newDiagram, cmd) = diagram.update(subMsg)
          this.copy(state = s.copy(progressView = p.copy(diagramDisplay = d.copy(mermaidDiagram = newDiagram)))) -> cmd.map(Msg.ForDiagram(_))
        case _ => (this, Cmd.None)
      }
    case InstancesManager.Msg.Reset              =>
      (InstancesManager.initial, Cmd.None)
  }

  def view(selectedWorkflowId: Option[String]): Html[InstancesManager.Msg] =
    div(cls := "column")(
      selectedWorkflowId match {
        case None =>
          div(cls := "box has-text-centered p-6")(
            p("Select a workflow from the menu to get started."),
          )

        case Some(wfId) =>
          div(cls := "box")(
            div(cls := "tabs")(
              ul(
                li(cls := "is-active")(a("Instances")),
                li()(a("Definition")),
              ),
            ),
            instanceInputView(wfId),
            state match {
              case InstancesManager.State.Ready =>
                div()

              case InstancesManager.State.Loading =>
                section(cls := "section is-medium has-text-centered")(
                  ReusableViews.loadingSpinner("Fetching instance details..."),
                )

              case InstancesManager.State.Failed(reason) =>
                div(cls := "notification is-danger is-light mt-4")(text(reason))

              case InstancesManager.State.InstanceLoaded(instance, progressView) =>
                div(
                  instanceDetailsView(instance, progressView),
                  progressVisualizationView(progressView),
                )
            },
          )
      },
    )

  // 2. Add the missing jsonStateViewer method
  private def jsonStateViewer(instance: WorkflowInstance): Html[InstancesManager.Msg] = {
    div(cls := "box mt-4")(
      h5("Instance State"),
      instance.state match {
        case Some(stateJson) =>
          pre(cls := "mt-2 content is-small")(
            code(stateJson.spaces2),
          )
        case None            =>
          div(cls := "notification is-light")(
            text("No state data available"),
          )
      },
    )
  }

  private def instanceInputView(workflowId: String): Html[InstancesManager.Msg] =
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
            cls := s"button is-primary ${if state == InstancesManager.State.Loading then "is-loading" else ""}",
            onClick(InstancesManager.Msg.LoadInstance(workflowId)),
            disabled(state == InstancesManager.State.Loading || instanceIdInput.trim.isEmpty),
          )("Load"),
        ),
        div(cls := "control")(
          button(
            cls := s"button is-info is-outlined ${if state == InstancesManager.State.Loading then "is-loading" else ""}",
            onClick(InstancesManager.Msg.CreateTestInstance(workflowId)),
            disabled(state == InstancesManager.State.Loading),
          )("Create Test Instance"),
        ),
      ),
    )

  private def instanceDetailsView(instance: WorkflowInstance, progressView: InstancesManager.ProgressView): Html[InstancesManager.Msg] =
    div(cls := "content mt-4")(
      h3(s"Instance: ${instance.id}"),
      ReusableViews.instanceField("Definition", Html.span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      ReusableViews.instanceField("Created", Html.span(instance.createdAt.getOrElse("Unknown"))),
      ReusableViews.instanceField("Updated", Html.span(instance.updatedAt.getOrElse("Unknown"))),
      instanceActionsView(progressView),
      instanceStateView(instance, progressView),
    )

  private def instanceActionsView(progressView: InstancesManager.ProgressView): Html[InstancesManager.Msg] =
    div(cls := "field is-grouped mt-4")(
      div(cls := "control")(
        button(
          cls := "button is-info is-small",
          onClick(InstancesManager.Msg.ToggleJsonState),
        )(
          progressView match {
            case InstancesManager.ProgressView.Loaded(_, InstancesManager.StateDisplay.Visible, _) => "Hide State"
            case _                                                                                 => "Show State"
          },
        ),
      ),
      div(cls := "control")(
        button(
          cls := "button is-success is-small",
          onClick(InstancesManager.Msg.ToggleMermaidViewer),
        )(
          progressView match {
            case InstancesManager.ProgressView.Loaded(_, _, InstancesManager.DiagramDisplay.Hidden) => "Show Diagram"
            case _                                                                                  => "Hide Diagram"
          },
        ),
      ),
    )

  private def instanceStateView(instance: WorkflowInstance, progressView: InstancesManager.ProgressView): Html[InstancesManager.Msg] =
    progressView match {
      case InstancesManager.ProgressView.Loaded(_, InstancesManager.StateDisplay.Visible, _) =>
        jsonStateViewer(instance)
      case _                                                                                 =>
        div()
    }

  private def progressVisualizationView(progressView: InstancesManager.ProgressView): Html[InstancesManager.Msg] =
    div(cls := "mt-5")(
      h4("Workflow Progress"),
      progressView match {
        case InstancesManager.ProgressView.Loading =>
          ReusableViews.loadingSpinner("Loading progress...")

        case InstancesManager.ProgressView.Failed(reason) =>
          div(cls := "notification is-warning is-light")(
            text(s"Could not load progress: $reason"),
          )

        case InstancesManager.ProgressView.Loaded(progress, _, diagramDisplay) =>
          div(
            progressSummaryView(progress),
            diagramView(diagramDisplay),
          )
      },
    )

  private def diagramView(diagramDisplay: InstancesManager.DiagramDisplay): Html[InstancesManager.Msg] =
    diagramDisplay match {
      case InstancesManager.DiagramDisplay.Hidden =>
        div()

      case InstancesManager.DiagramDisplay.Loading =>
        div(cls := "mt-4")(
          text("Loading diagram..."),
        )

      case InstancesManager.DiagramDisplay.Rendered(mermaidUrl, diagram) =>
        mermaidDiagramView(mermaidUrl, diagram)
    }

  private def progressSummaryView(progress: ProgressResponse): Html[InstancesManager.Msg] =
    div(cls := "box")(
      h5("Progress Summary"),
      div(cls := "content")(
        p(s"Type: ${progress.progressType}"),
        p(s"Completed: ${progress.isCompleted}"),
        p(s"Steps: ${progress.steps.length}"),
      ),
      details(cls := "mt-2")(
        summary(cls := "button is-small is-light")("📋 View Raw Data"),
        pre(cls := "mt-2 content is-small")(
          code(createProgressJson(progress).spaces2),
        ),
      ),
    )

  private def createProgressJson(progress: ProgressResponse): Json =
    Json.obj(
      "progressType" -> Json.fromString(progress.progressType),
      "isCompleted"  -> Json.fromBoolean(progress.isCompleted),
      "steps"        -> Json.arr(
        progress.steps.map(step =>
          Json.obj(
            "stepType" -> Json.fromString(step.stepType),
            "meta"     -> Json.obj(
              "name"          -> step.meta.name.fold(Json.Null)(Json.fromString),
              "signalName"    -> step.meta.signalName.fold(Json.Null)(Json.fromString),
              "operationName" -> step.meta.operationName.fold(Json.Null)(Json.fromString),
              "error"         -> step.meta.error.fold(Json.Null)(Json.fromString),
              "description"   -> step.meta.description.fold(Json.Null)(Json.fromString),
            ),
            "result"   -> step.result.fold(Json.Null)(result =>
              Json.obj(
                "status" -> Json.fromString(result.status),
                "index"  -> Json.fromInt(result.index),
                "state"  -> result.state.fold(Json.Null)(Json.fromString),
              ),
            ),
          ),
        )*,
      ),
    )

  private def mermaidDiagramView(mermaidUrl: String, diagramView: MermaidDiagramView): Html[InstancesManager.Msg] =
    div(cls := "box mt-4")(
      h5("🎨 Workflow Diagram"),

      // Action buttons
      div(cls := "field is-grouped mb-4")(
        div(cls := "control")(
          a(
            cls    := "button is-success is-small",
            href   := mermaidUrl,
            target := "_blank",
          )("🔗 View in Mermaid Live"),
        ),
      ),
      diagramView.view.map(Msg.ForDiagram(_)),
    )
}

object InstancesManager {
  def initial: InstancesManager =
    InstancesManager(
      instanceIdInput = "",
      state = State.Ready,
    )

  enum State {
    case Ready
    case Loading
    case Failed(reason: String)
    case InstanceLoaded(instance: WorkflowInstance, progressView: ProgressView)
  }

  enum ProgressView {
    case Loading
    case Failed(reason: String)
    case Loaded(progress: ProgressResponse, stateDisplay: StateDisplay, diagramDisplay: DiagramDisplay)
  }

  enum StateDisplay {
    case Hidden
    case Visible
  }

  enum DiagramDisplay {
    case Hidden
    case Loading
    case Rendered(mermaidUrl: String, mermaidDiagram: MermaidDiagramView)
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(workflowId: String)
    case InstanceLoaded(result: Either[String, WorkflowInstance])
    case ProgressLoaded(result: Either[String, ProgressResponse])
    case ToggleJsonState
    case ToggleMermaidViewer
    case CreateTestInstance(workflowId: String)
    case Reset
    case ForDiagram(msg: MermaidDiagramView.Msg)
  }

  object Http {

    def loadInstance(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http
          .getInstance(workflowId, instanceId)
          .map(instance => Msg.InstanceLoaded(Right(instance)))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage))),
      )
    }

    def loadProgress(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http
          .getProgress(workflowId, instanceId)
          .map(progress => Msg.ProgressLoaded(Right(progress)))
          .handleError(err => Msg.ProgressLoaded(Left(err.getMessage))),
      )
    }

    def createTestInstance(workflowId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http
          .createTestInstance(workflowId)
          .map(instance => Msg.InstanceLoaded(Right(instance)))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage))),
      )
    }
  }
}
