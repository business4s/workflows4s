package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import workflow4s.wio.{KnockerUpper, WorkflowContext}

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps

class PekkoKnockerUpper[Ctx <: WorkflowContext](timers: TimerScheduler[WorkflowBehavior.Command[Ctx]], context: ActorContext[_])
    extends KnockerUpper {

  // TODO logging? At least for errors?
  override def registerWakeup(at: Instant): IO[Unit] = IO({
//    val now     = Instant.now()
//    val delay   = Duration.between(now, at).toScala
//    val replyTo = context.spawnAnonymous(Behaviors.ignore)
//    timers.startSingleTimer(WorkflowBehavior.Command.Wakeup(replyTo), delay)
    // TODO implementation above doesnt work
    // Unsupported access to ActorContext operation from the outside of Actor
  })
}
