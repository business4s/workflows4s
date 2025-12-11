package workflows4s.example.docs.wakeups

import cats.effect.IO
import workflows4s.catseffect.CatsEffect.given
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

object QuartzKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper

  val scheduler: org.quartz.Scheduler            = ???
  val dispatcher: cats.effect.std.Dispatcher[IO] = ???

  scheduler.start()

  // allows matching wakeups between restarts
  val knockerUpper = new QuartzKnockerUpper(scheduler, dispatcher)

  val runtime: WorkflowRuntime[IO, MyWorkflowCtx] = createRuntime(knockerUpper)

  val initialization: IO[Unit] = knockerUpper.initialize(Seq(runtime))
// docs_end

}
