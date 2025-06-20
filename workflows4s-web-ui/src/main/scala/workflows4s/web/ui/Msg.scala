package workflows4s.web.ui

import workflows4s.web.ui.models.{WorkflowDefinition, WorkflowInstance}

enum Msg {
  // Application lifecycle
  case NoOp
  case LoadWorkflows

  // Workflow loading results
  case WorkflowsLoadedSuccess(workflows: List[WorkflowDefinition])
  case WorkflowsLoadedFailure(reason: String)

  // User interactions
  case WorkflowSelected(workflowId: String)
  case InstanceIdChanged(instanceId: String)
  case LoadInstance
  case ToggleJsonState

  // Instance loading results
  case InstanceLoadedSuccess(instance: WorkflowInstance)
  case InstanceLoadedFailure(reason: String)
}