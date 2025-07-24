package workflows4s.example.docs.wakeups

import cats.effect.IO
import workflows4s.runtime.WorkflowRuntime
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.WorkflowContext

object common {

  trait MyWorkflowId
  trait MyWorkflowCtx extends WorkflowContext

  // docs_start
  def createRuntime(knockerUpper: KnockerUpper.Agent): WorkflowRuntime[IO, MyWorkflowCtx] = ???
  // docs_end

}
