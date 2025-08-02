package workflows4s.runtime

case class WorkflowInstanceId(templateId: String, instanceId: String) {
  override def toString: String = s"$templateId:$instanceId"
}
