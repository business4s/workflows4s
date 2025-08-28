package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.{ProgressResponse, WorkflowInstance}
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.{MermaidHelper, MermaidJS}
import scala.scalajs.js
import io.circe.Json

import scala.concurrent.duration.DurationInt

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

    case InstancesManager.Msg.InstanceLoaded(Right(instance)) =>
      val updated = this.copy(state = InstancesManager.State.InstanceLoaded(instance, InstancesManager.ProgressView.Loading))
      val cmd     = InstancesManager.Http.loadProgress(instance.definitionId, instance.id)
      (updated, cmd)

    case InstancesManager.Msg.InstanceLoaded(Left(err)) =>
      (this.copy(state = InstancesManager.State.Failed(err)), Cmd.None)

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

    case InstancesManager.Msg.RetryMermaidRender(mermaid) =>
      val renderCmd = if MermaidHelper.mermaidAvailable then {
        Cmd.Run(renderMermaidDiagram(mermaid))
      } else {
        Cmd.Run(IO.sleep(500.millis).map(_ => InstancesManager.Msg.RetryMermaidRender(mermaid)))
      }
      (this, renderCmd)

    case InstancesManager.Msg.MermaidRendered(svg) =>
      state match {
        case InstancesManager.State.InstanceLoaded(
              instance,
              InstancesManager.ProgressView.Loaded(progress, stateDisplay, InstancesManager.DiagramDisplay.LoadingRender(mermaid)),
            ) =>
          val diagramDisplay = InstancesManager.DiagramDisplay.Rendered(mermaid, svg)
          val progressView   = InstancesManager.ProgressView.Loaded(progress, stateDisplay, diagramDisplay)
          val updated        = this.copy(state = InstancesManager.State.InstanceLoaded(instance, progressView))
          (updated, Cmd.None)
        case _ => (this, Cmd.None)
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
              val mermaidCode    = generateMermaidFromProgress(progress)
              val loadingDisplay = InstancesManager.DiagramDisplay.LoadingRender(mermaidCode)
              val renderCmd      = if MermaidHelper.mermaidAvailable then {
                Cmd.Run(renderMermaidDiagram(mermaidCode))
              } else {
                Cmd.Run(IO.sleep(500.millis).map(_ => InstancesManager.Msg.RetryMermaidRender(mermaidCode)))
              }
              (loadingDisplay, renderCmd)
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

    case InstancesManager.Msg.Reset =>
      (InstancesManager.initial, Cmd.None)
  }

  private def renderMermaidDiagram(mermaidCode: String): IO[InstancesManager.Msg] = {
    if MermaidHelper.mermaidAvailable then {
      println("Mermaid available, attempting to render.")
      println(s"Mermaid code to render: $mermaidCode")

      val renderTask = for {
        _            <- IO(MermaidJS.initialize(js.Dynamic.literal("startOnLoad" -> false, "htmlLabels" -> false)))
        renderResult <- MermaidHelper.fromPromise(MermaidJS.render("mermaid-diagram", mermaidCode))
      } yield {
        println(s"Mermaid render successful, SVG length: ${renderResult.svg.length}")
        InstancesManager.Msg.MermaidRendered(renderResult.svg)
      }

      renderTask.handleError { ex =>
        println(s"Mermaid rendering failed: ${ex.getMessage}")
        InstancesManager.Msg.MermaidRendered(s"<div style='color: red; padding: 20px;'>Failed to render diagram: ${ex.getMessage}</div>")
      }
    } else {
      println("Mermaid isn't ready, retrying...")
      IO.pure(InstancesManager.Msg.RetryMermaidRender(mermaidCode))
    }
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
            instanceInputView(wfId),
            testInstanceView(wfId),
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

  private def instanceInputView(workflowId: String): Html[InstancesManager.Msg] =
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
      div(cls := "control")(
        label(cls := "label")(" "),
        button(
          cls := s"button is-primary ${if state == InstancesManager.State.Loading then "is-loading" else ""}",
          onClick(InstancesManager.Msg.LoadInstance(workflowId)),
          disabled(state == InstancesManager.State.Loading || instanceIdInput.trim.isEmpty),
        )("Load"),
      ),
    )

  private def testInstanceView(workflowId: String): Html[InstancesManager.Msg] =
    div(cls := "field mt-4")(
      label(cls := "label")("Quick Test"),
      div(cls := "control")(
        button(
          cls := s"button is-info is-outlined ${if state == InstancesManager.State.Loading then "is-loading" else ""}",
          onClick(InstancesManager.Msg.CreateTestInstance(workflowId)),
          disabled(state == InstancesManager.State.Loading),
        )("Create Test Instance"),
      ),
      p(cls := "help")("Creates a new test instance for this workflow"),
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

      case InstancesManager.DiagramDisplay.LoadingRender(mermaid) =>
        mermaidDiagramView(mermaid, None)

      case InstancesManager.DiagramDisplay.Rendered(mermaid, svg) =>
        mermaidDiagramView(mermaid, Some(svg))
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

  private def mermaidDiagramView(mermaid: String, renderedSvg: Option[String]): Html[InstancesManager.Msg] =
    div(cls := "box mt-4")(
      h5("🎨 Workflow Diagram"),

      // Action buttons
      div(cls := "field is-grouped mb-4")(
        div(cls := "control")(
          a(
            cls    := "button is-success is-small",
            href   := createMermaidLiveUrl(mermaid),
            target := "_blank",
          )("🔗 View in Mermaid Live"),
        ),
      ),
      renderedSvg match {
        case Some(svg) =>
          div(cls := "has-text-centered p-4")().innerHtml(svg)
        case None      =>
          div(cls := "notification is-info is-light has-text-centered")(
            text("🔄 Rendering diagram..."),
          )
      },

      // Code view
      details(cls := "mt-4")(
        summary(cls := "button is-small is-light")("📋 View Mermaid Code"),
        pre(cls := "mt-2")(
          code(mermaid),
        ),
      ),
    )

  private def createMermaidLiveUrl(mermaidCode: String): String = {
    import java.nio.charset.StandardCharsets
    import java.util.Base64
    import io.circe.Json

    val json      = Json.obj("code" -> Json.fromString(mermaidCode)).noSpaces
    val encoded   = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))
    val base64url = encoded
      .replace('+', '-')
      .replace('/', '_')

    s"https://mermaid.live/edit#base64:$base64url"
  }

  private def jsonStateViewer(instance: WorkflowInstance): Html[InstancesManager.Msg] =
    div(cls := "box mt-4")(
      h5("Instance State"),
      pre(cls := "content is-small")(
        code(instance.state.map(_.spaces2).getOrElse("No state available.")),
      ),
    )

  private def generateMermaidFromProgress(progress: ProgressResponse): String = {
    val builder = new StringBuilder()
    builder.append("flowchart TD\n")

    // Start node
    builder.append("  node_start@{ shape: circle, label: \"Start\"}\n")

    // Generate nodes for each step
    val stepNodes = progress.steps.zipWithIndex.map { case (step, index) =>
      val stepName   = step.meta.name.getOrElse(s"Step ${index + 1}")
      val nodeId     = s"node_$index"
      val isExecuted = step.result.exists(_.status == "Completed")

      val nodeShape = step.stepType match {
        case "HandleSignal" => s"""$nodeId@{ shape: stadium, label: "fa:fa-envelope $stepName"}"""
        case "Timer"        => s"""$nodeId@{ shape: stadium, label: "fa:fa-clock $stepName"}"""
        case "Pure"         => s"""$nodeId["$stepName"]"""
        case "RunIO"        => s"""$nodeId["$stepName"]"""
        case "Fork"         => s"""$nodeId@{ shape: hex, label: "$stepName"}"""
        case "Parallel"     => s"""$nodeId@{ shape: fork, label: "$stepName"}"""
        case "Retried"      => s"""$nodeId["fa:fa-redo $stepName"]"""
        case _              => s"""$nodeId["$stepName"]"""
      }

      val nodeWithClass = if isExecuted then nodeShape + ":::executed" else nodeShape
      builder.append(s"  $nodeWithClass\n")
      nodeId
    }

    // End node
    builder.append("  node_end@{ shape: circle, label: \"End\"}\n")

    // Generate connections
    if stepNodes.nonEmpty then {
      builder.append(s"  node_start --> ${stepNodes.head}\n")

      for i <- stepNodes.indices.init do {
        builder.append(s"  ${stepNodes(i)} --> ${stepNodes(i + 1)}\n")
      }

      builder.append(s"  ${stepNodes.last} --> node_end\n")
    } else {
      builder.append("  node_start --> node_end\n")
    }

    // Add error handling connections if any steps have errors
    val hasErrors = progress.steps.exists(_.meta.error.isDefined)

    if hasErrors then {
      builder.append("  node_error[\"Error Handler\"]\n")
      progress.steps.zipWithIndex.foreach { case (step, index) =>
        step.meta.error.foreach { errorName =>
          builder.append(s"  node_$index -.->|\"fa:fa-bolt $errorName\"| node_error\n")
        }
      }
      builder.append("  node_error --> node_end\n")
    }

    // Add styling
    builder.append("  classDef executed fill:#0e0\n")

    builder.toString()
  }
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
    case LoadingRender(mermaid: String)
    case Rendered(mermaid: String, svg: String)
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(workflowId: String)
    case InstanceLoaded(result: Either[String, WorkflowInstance])
    case ProgressLoaded(result: Either[String, ProgressResponse])
    case RetryMermaidRender(mermaidCode: String)
    case MermaidRendered(svg: String)
    case ToggleJsonState
    case ToggleMermaidViewer
    case CreateTestInstance(workflowId: String)
    case Reset
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
