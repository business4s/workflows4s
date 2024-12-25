package workflows4s.example.docs.wakeups

import cats.effect.{IO, ResourceIO}
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

object SleepingKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.SleepingKnockerUpper

  // all sleeps will be canceled on release
  val knockerUpperResource: ResourceIO[SleepingKnockerUpper[MyWorkflowId]] = SleepingKnockerUpper.create()

  knockerUpperResource.use(knockerUpper => {
    val runtime: WorkflowRuntime[IO, MyWorkflowCtx, MyWorkflowId] = createRuntime(knockerUpper)
    val init: IO[Unit]                                            = knockerUpper.initialize(id => runtime.createInstance(id).flatMap(_.wakeup()))
    ???
  })
  // docs_end

}
