package workflows4s.web.ui.subs

import cats.effect.IO
import tyrian.*
import tyrian.Html.*
import workflows4s.web.api.model.{WorkflowInstance, ProgressResponse}
import workflows4s.web.ui.components.ReusableViews

final case class InstancesManager(
    instanceIdInput: String,
    state: InstancesManager.State,
    showJsonState: Boolean,
    progressState: InstancesManager.ProgressState,
    showMermaidViewer: Boolean,
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
      (updated, Cmd.None)

    case InstancesManager.Msg.MermaidLoaded(Left(err)) =>
      (this, Cmd.None)

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
      ReusableViews.instanceField("Definition", span(instance.definitionId)),
      ReusableViews.instanceField("Status", ReusableViews.statusBadge(instance.status)),
      ReusableViews.instanceField("Created", span(instance.createdAt.getOrElse("Unknown"))),
      ReusableViews.instanceField("Updated", span(instance.updatedAt.getOrElse("Unknown"))),
      
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

  // Fix: Remove unused parameter
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
      h5("Workflow Diagram"),
      div(cls := "content")(
        pre(cls := "language-mermaid")(
          code(mermaid)
        ),
      ),
      p(cls := "help")(
        text("Copy this Mermaid code to "),
        a(href := "https://mermaid.live", target := "_blank")("mermaid.live"),
        text(" to view the rendered diagram.")
      ),
    )

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