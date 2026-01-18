package workflows4s.example.docs.wakeups

import cats.effect.IO
import workflows4s.runtime.WorkflowRuntime
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.cats.IOWorkflowContext

object common {

  trait MyWorkflowId
  trait MyWorkflowCtx extends IOWorkflowContext {
    type State = String
  }

  // docs_start
  def createRuntime(knockerUpper: KnockerUpper.Agent[IO]): WorkflowRuntime[IO, MyWorkflowCtx] = ???
  // docs_end

}
