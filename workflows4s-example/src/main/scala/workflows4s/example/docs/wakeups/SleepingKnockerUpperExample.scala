package workflows4s.example.docs.wakeups

import cats.effect.IO
import workflows4s.cats.CatsEffect.given
import workflows4s.example.docs.wakeups.common.*
import workflows4s.runtime.WorkflowRuntime

import scala.annotation.nowarn

@nowarn("msg=unused")
object SleepingKnockerUpperExample {

  // docs_start
  import workflows4s.runtime.wakeup.SleepingKnockerUpper

  val knockerUpperIO: IO[SleepingKnockerUpper[IO]] = SleepingKnockerUpper.create[IO]

  knockerUpperIO.flatMap { knockerUpper =>
    val runtime: WorkflowRuntime[IO, MyWorkflowCtx] = createRuntime(knockerUpper)
    val init: IO[Unit]                              = knockerUpper.initialize(Seq(runtime))
    ???
  }
  // docs_end

}
