package workflows4s.example.docs.wakeups

import cats.effect.IO
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime
import workflows4s.runtime.wakeup.quartz.StringCodec

object QuartzKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper

  val scheduler: org.quartz.Scheduler            = ???
  given StringCodec[MyWorkflowId]                = ???
  val dispatcher: cats.effect.std.Dispatcher[IO] = ???

  scheduler.start()

  // allows matching wakeups between restarts
  val runtimeId    = QuartzKnockerUpper.RuntimeId("my-runtime")
  val knockerUpper = new QuartzKnockerUpper(runtimeId, scheduler, dispatcher)

  val runtime: WorkflowRuntime[IO, MyWorkflowCtx, MyWorkflowId] = createRuntime(knockerUpper)

  val initialization: IO[Unit] = knockerUpper.initialize(id => runtime.createInstance(id).flatMap(_.wakeup()))
// docs_end

}
