package workflows4s.web.ui.models

enum AppState {
  case Initializing
  case LoadingWorkflows
  case Ready(error: Option[String])
  case LoadingInstance
}

final case class Model(
    appState: AppState,
    workflows: List[WorkflowDefinition],
    selectedWorkflowId: Option[String],
    instanceIdInput: String,
    currentInstance: Option[WorkflowInstance],
    instanceError: Option[String],
    showJsonState: Boolean,
) {
  def withAppState(newState: AppState): Model = copy(appState = newState)
  def withSelectedWorkflow(id: Option[String]): Model = copy(selectedWorkflowId = id, currentInstance = None, instanceError = None, instanceIdInput = "")
  def withInstanceIdInput(text: String): Model = copy(instanceIdInput = text)
  def withInstance(instance: WorkflowInstance): Model = copy(currentInstance = Some(instance), instanceError = None, appState = AppState.Ready(None))
  def withInstanceError(error: String): Model = copy(instanceError = Some(error), currentInstance = None, appState = AppState.Ready(None))
  def loadingWorkflows: Model = withAppState(AppState.LoadingWorkflows)
  def loadingInstance: Model = copy(appState = AppState.LoadingInstance, instanceError = None)
  def workflowsReady(newWorkflows: List[WorkflowDefinition]): Model =
    copy(
      appState = AppState.Ready(None),
      workflows = newWorkflows,
      selectedWorkflowId = newWorkflows.headOption.map(_.id),
    )
  def workflowsFailed(error: String): Model = copy(appState = AppState.Ready(Some(error)))
  def toggleJsonState: Model = copy(showJsonState = !showJsonState)
}

object Model {
  def initial: Model = Model(
    appState = AppState.Initializing,
    workflows = Nil,
    selectedWorkflowId = None,
    instanceIdInput = "",
    currentInstance = None,
    instanceError = None,
    showJsonState = false,
  )
}