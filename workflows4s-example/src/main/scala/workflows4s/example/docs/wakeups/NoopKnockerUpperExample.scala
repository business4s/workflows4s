package workflows4s.example.docs.wakeups

import cats.effect.{IO, ResourceIO}
import workflows4s.example.docs.wakeups.QuartzKnockerUpperExample.knockerUpper
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime
import workflows4s.runtime.wakeup.KnockerUpper

object NoopKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.NoOpKnockerUpper
  val knockerUpper = NoOpKnockerUpper.Agent

  val runtime: WorkflowRuntime[IO, MyWorkflowCtx, MyWorkflowId] = createRuntime(knockerUpper)
  // docs_end

}
