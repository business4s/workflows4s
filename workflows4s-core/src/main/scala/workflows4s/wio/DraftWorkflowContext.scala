package workflows4s.wio

object DraftWorkflowContext extends WorkflowContext {
  type Effect = [A] =>> Nothing
}
