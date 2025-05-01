package workflows4s.runtime.registry

trait InMemoryWorkflowRegistry {

  def getAgent(workflowType: String): WorkflowRegistry.Agent[Unit]

  def getWorkflows(): List[String]

}
