package workflows4s.runtime

/** Composite identifier for a workflow instance. The `templateId` identifies the workflow definition (from [[WorkflowRuntime.templateId]]) and
  * `instanceId` identifies the specific instance within it.
  */
case class WorkflowInstanceId(templateId: String, instanceId: String) {
  override def toString: String = s"$templateId:$instanceId"
}
