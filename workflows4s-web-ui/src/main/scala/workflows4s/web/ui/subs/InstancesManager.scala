package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.{WorkflowInstance, ProgressResponse}
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.{MermaidJS, MermaidHelper}
import scala.scalajs.js

 
import scala.concurrent.duration.DurationInt

final case class InstancesManager(
    instanceIdInput: String,
    state: InstancesManager.State,
    showJsonState: Boolean,
    progressState: InstancesManager.ProgressState,
    showMermaidViewer: Boolean,
    renderedMermaidSvg: Option[String] = None,
) {

  def update(msg: InstancesManager.Msg): (InstancesManager, Cmd[IO, InstancesManager.Msg]) = msg match {
    case InstancesManager.Msg.InstanceIdChanged(id) =>
      (this.copy(instanceIdInput = id), Cmd.None)

    case InstancesManager.Msg.LoadInstance(workflowId) =>
      val updated = this.copy(state = InstancesManager.State.Loading)
      val cmd     = InstancesManager.Http.loadInstance(workflowId, instanceIdInput)
      (updated, cmd)

    case InstancesManager.Msg.InstanceLoaded(Right(instance)) =>
      val updated = this.copy(state = InstancesManager.State.Success(instance))
      val cmd     = InstancesManager.Http.loadProgress(instance.definitionId, instance.id)
      (updated, cmd)

    case InstancesManager.Msg.InstanceLoaded(Left(err)) =>
      (this.copy(state = InstancesManager.State.Failed(err)), Cmd.None)

    case InstancesManager.Msg.ProgressLoaded(Right(progress)) =>
      (this.copy(progressState = InstancesManager.ProgressState.Success(progress)), Cmd.None)

    case InstancesManager.Msg.ProgressLoaded(Left(err)) =>
      (this.copy(progressState = InstancesManager.ProgressState.Failed(err)), Cmd.None)

    case InstancesManager.Msg.MermaidLoaded(Right(mermaid)) =>
      val updated = progressState match {
        case InstancesManager.ProgressState.Success(progress) =>
          this.copy(progressState = InstancesManager.ProgressState.SuccessWithMermaid(progress, mermaid))
        case _ => this
      }
      // Start Mermaid rendering process
      val renderCmd = if (MermaidHelper.mermaidAvailable) {
        Cmd.Run(renderMermaidDiagram(mermaid))
      } else {
        // Use IO.sleep for retry delay
        Cmd.Run(IO.sleep(500.millis).map(_ => InstancesManager.Msg.RetryMermaidRender(mermaid)))
      }
      (updated, renderCmd)

    case InstancesManager.Msg.MermaidLoaded(Left(err)) =>
      (this, Cmd.None)

    case InstancesManager.Msg.RetryMermaidRender(mermaid) =>
      val renderCmd = if (MermaidHelper.mermaidAvailable) {
        Cmd.Run(renderMermaidDiagram(mermaid))
      } else {
        Cmd.Run(IO.sleep(500.millis).map(_ => InstancesManager.Msg.RetryMermaidRender(mermaid)))
      }
      (this, renderCmd)

    case InstancesManager.Msg.MermaidRendered(svg) =>
      (this.copy(renderedMermaidSvg = Some(svg)), Cmd.None)

    case InstancesManager.Msg.ToggleJsonState =>
      (this.copy(showJsonState = !showJsonState), Cmd.None)

    case InstancesManager.Msg.ToggleMermaidViewer =>
      val updated = this.copy(showMermaidViewer = !showMermaidViewer)
      val cmd = if (!showMermaidViewer && progressState.isInstanceSuccess) {
        progressState match {
          case InstancesManager.ProgressState.Success(progress) =>
            state match {
              case InstancesManager.State.Success(instance) =>
                InstancesManager.Http.loadMermaid(instance.definitionId, instance.id)
              case _ => Cmd.None
            }
          case _ => Cmd.None
        }
      } else Cmd.None
      (updated, cmd)

    case InstancesManager.Msg.CreateTestInstance(workflowId) =>
      val updated = this.copy(state = InstancesManager.State.Loading)
      val cmd     = InstancesManager.Http.createTestInstance(workflowId)
      (updated, cmd)

    case InstancesManager.Msg.Reset =>
      (InstancesManager.initial, Cmd.None)
  }
 
private def renderMermaidDiagram(mermaidCode: String): IO[InstancesManager.Msg] = {
  if (MermaidHelper.mermaidAvailable) {
    println("Mermaid available, attempting to render.")
    println(s"Mermaid code to render: $mermaidCode")

    val renderTask = for {
      
      _ <- IO(MermaidJS.initialize(js.Dynamic.literal("startOnLoad" -> false, "htmlLabels" -> false)))
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
              case InstancesManager.State.Loading =>
                section(cls := "section is-medium has-text-centered")(
                  ReusableViews.loadingSpinner("Fetching instance details..."),
                )

              case InstancesManager.State.Failed(reason) =>
                div(cls := "notification is-danger is-light mt-4")(text(reason))

              case InstancesManager.State.Success(instance) =>
                div(
                  instanceDetailsView(instance),
                  progressVisualizationView(),
                )

              case InstancesManager.State.Ready =>
                div()
            },
          )
      }
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

  private def instanceDetailsView(instance: WorkflowInstance): Html[InstancesManager.Msg] =
    div(cls := "content mt-4")(
      h3(s"Instance: ${instance.id}"),
     
      ReusableViews.instanceField("Definition", Html.span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      ReusableViews.instanceField("Created", Html.span(instance.createdAt.getOrElse("Unknown"))),
      ReusableViews.instanceField("Updated", Html.span(instance.updatedAt.getOrElse("Unknown"))),

      div(cls := "field is-grouped mt-4")(
        div(cls := "control")(
          button(
            cls := "button is-info is-small",
            onClick(InstancesManager.Msg.ToggleJsonState),
          )(if showJsonState then "Hide State" else "Show State"),
        ),
        div(cls := "control")(
          button(
            cls := "button is-success is-small",
            onClick(InstancesManager.Msg.ToggleMermaidViewer),
          )(if showMermaidViewer then "Hide Diagram" else "Show Diagram"),
        ),
      ),

      if showJsonState then jsonStateViewer(instance) else div(),
    )

  private def progressVisualizationView(): Html[InstancesManager.Msg] =
    div(cls := "mt-5")(
      h4("Workflow Progress"),
      progressState match {
        case InstancesManager.ProgressState.Loading =>
          ReusableViews.loadingSpinner("Loading progress...")

        case InstancesManager.ProgressState.Failed(reason) =>
          div(cls := "notification is-warning is-light")(
            text(s"Could not load progress: $reason")
          )

        case InstancesManager.ProgressState.Success(progress) =>
          div(
            progressSummaryView(progress),
            if showMermaidViewer then div(cls := "mt-4")(
              text("Loading diagram...")
            ) else div(),
          )

        case InstancesManager.ProgressState.SuccessWithMermaid(progress, mermaid) =>
          div(
            progressSummaryView(progress),
            if showMermaidViewer then mermaidDiagramView(mermaid) else div(),
          )

        case InstancesManager.ProgressState.Ready =>
          div()
      }
    )

  private def progressSummaryView(progress: ProgressResponse): Html[InstancesManager.Msg] =
    div(cls := "box")(
      h5("Progress Summary"),
      pre(cls := "content is-small")(
        code(progress.progress.spaces2)
      ),
    )
 
private def mermaidDiagramView(mermaid: String): Html[InstancesManager.Msg] =
  div(cls := "box mt-4")(
    h5("🎨 Workflow Diagram"),
    
    // Action buttons
    div(cls := "field is-grouped mb-4")(
      div(cls := "control")(
        a(
          cls := "button is-success is-small",
          href := createMermaidLiveUrl(mermaid),
          target := "_blank"
        )("🔗 View in Mermaid Live"),
      ),
    ),
    
 
    renderedMermaidSvg match {
      case Some(svg) =>
        div(cls := "has-text-centered p-4")().innerHtml(svg)
      case None =>
        div(cls := "notification is-info is-light has-text-centered")(
          text("🔄 Rendering diagram...")
        )
    },
    
    // Code view
    details(cls := "mt-4")(
      summary(cls := "button is-small is-light")("📋 View Mermaid Code"),
      pre(cls := "mt-2")(
        code(mermaid)
      ),
    ),
  )
  private def createMermaidLiveUrl(mermaidCode: String): String = {
    val encoded = java.net.URLEncoder.encode(mermaidCode, "UTF-8")
    s"https://mermaid.live/edit#code:$encoded"
  }

  private def jsonStateViewer(instance: WorkflowInstance): Html[InstancesManager.Msg] =
    div(cls := "box mt-4")(
      h5("Instance State"),
      pre(cls := "content is-small")(
        code(instance.state.map(_.spaces2).getOrElse("No state available."))
      ),
    )
}

object InstancesManager {
  def initial: InstancesManager =
    InstancesManager(
      instanceIdInput = "",
      state = State.Ready,
      showJsonState = false,
      progressState = ProgressState.Ready,
      showMermaidViewer = false,
      renderedMermaidSvg = None,
    )

  enum State {
    case Ready
    case Loading
    case Failed(reason: String)
    case Success(instance: WorkflowInstance)
  }

  enum ProgressState {
    case Ready
    case Loading
    case Failed(reason: String)
    case Success(progress: ProgressResponse)
    case SuccessWithMermaid(progress: ProgressResponse, mermaid: String)

    def isInstanceSuccess: Boolean = this match {
      case Success(_) | SuccessWithMermaid(_, _) => true
      case _ => false
    }
  }

  enum Msg {
    case InstanceIdChanged(id: String)
    case LoadInstance(workflowId: String)
    case InstanceLoaded(result: Either[String, WorkflowInstance])
    case ProgressLoaded(result: Either[String, ProgressResponse])
    case MermaidLoaded(result: Either[String, String])
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
        workflows4s.web.ui.http.Http.getInstance(workflowId, instanceId)
          .map(instance => Msg.InstanceLoaded(Right(instance)))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage)))
      )
    }

    def loadProgress(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http.getProgress(workflowId, instanceId)
          .map(progress => Msg.ProgressLoaded(Right(progress)))
          .handleError(err => Msg.ProgressLoaded(Left(err.getMessage)))
      )
    }

    def loadMermaid(workflowId: String, instanceId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http.getProgressAsMermaid(workflowId, instanceId)
          .map(mermaid => Msg.MermaidLoaded(Right(mermaid)))
          .handleError(err => Msg.MermaidLoaded(Left(err.getMessage)))
      )
    }

    def createTestInstance(workflowId: String): Cmd[IO, Msg] = {
      Cmd.Run(
        workflows4s.web.ui.http.Http.createTestInstance(workflowId)
          .map(instance => Msg.InstanceLoaded(Right(instance)))
          .handleError(err => Msg.InstanceLoaded(Left(err.getMessage)))
      )
    }
  }
}