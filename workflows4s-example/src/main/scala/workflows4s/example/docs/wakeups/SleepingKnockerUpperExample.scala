package workflows4s.example.docs.wakeups

import cats.effect.{IO, ResourceIO}
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

import scala.annotation.nowarn

@nowarn("msg=unused")
object SleepingKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.SleepingKnockerUpper

  // all sleeps will be canceled on release
  val knockerUpperResource: ResourceIO[SleepingKnockerUpper] = SleepingKnockerUpper.create()

  knockerUpperResource.use(knockerUpper => {
    val runtime: WorkflowRuntime[IO, MyWorkflowCtx] = createRuntime(knockerUpper)
    val init: IO[Unit]                              = knockerUpper.initialize(Seq(runtime))
    ???
  })
  // docs_end

}
