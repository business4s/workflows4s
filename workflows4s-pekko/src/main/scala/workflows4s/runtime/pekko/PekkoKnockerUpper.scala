package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import workflow4s.wio.{KnockerUpper, WorkflowContext}

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps

class PekkoKnockerUpper[Ctx <: WorkflowContext](timers: TimerScheduler[WorkflowBehavior.Command[Ctx]]) extends KnockerUpper {

  override def registerWakeup(at: Instant): IO[Unit] = IO({
    val now   = Instant.now()
    val delay = Duration.between(now, at).toScala
    timers.startSingleTimer(WorkflowBehavior.Command.Wakeup(), delay)
  })
}
