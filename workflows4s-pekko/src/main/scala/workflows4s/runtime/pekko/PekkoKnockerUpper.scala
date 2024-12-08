package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import workflow4s.runtime.wakeup.KnockerUpper
import workflow4s.wio.WorkflowContext

import java.time.{Duration, Instant}
import java.util.UUID
import scala.jdk.DurationConverters.JavaDurationOps

class PekkoKnockerUpper[Ctx <: WorkflowContext](timers: TimerScheduler[WorkflowBehavior.Command[Ctx]], context: ActorContext[?])
    extends KnockerUpper {

  // TODO logging? At least for errors?
  override def registerWakeup(at: Instant): IO[Unit] = IO({
    val now = Instant.now()
    val delay = Duration.between(now, at).toScala
    // can't do context.spawn because this code runs out of actor's thread. Shouldn't make any difference.
    val replyTo = context.system.systemActorOf(Behaviors.ignore, s"knocker-upper-wakeup-${UUID.randomUUID()}")
    timers.startSingleTimer(WorkflowBehavior.Command.Wakeup(replyTo), delay)
  }).evalOn(context.executionContext)
}
