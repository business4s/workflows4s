package workflows4s.wio

object DraftWorkflowContext extends WorkflowContext {
  type F[A] = cats.Id[A]
}
