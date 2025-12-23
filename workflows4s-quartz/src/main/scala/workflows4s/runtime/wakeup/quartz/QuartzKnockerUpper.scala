package workflows4s.runtime.wakeup.quartz

import cats.effect.IO
import cats.effect.std.Dispatcher
import org.quartz.*
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant
import java.util.Date

class QuartzKnockerUpper(scheduler: Scheduler, dispatcher: Dispatcher[IO]) extends KnockerUpper.Agent[IO] with KnockerUpper.Process[IO, IO[Unit]] {
  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = IO {
    val jobKey = new JobKey(id.instanceId)
    at match {
      case Some(instant) =>
        val trigger = TriggerBuilder
          .newTrigger()
          .withIdentity(id.instanceId)
          .startAt(Date.from(instant))
          .build()

        if scheduler.checkExists(jobKey) then {
          scheduler.rescheduleJob(trigger.getKey, trigger)
          ()
        } else {
          val jobDetail = JobBuilder
            .newJob(classOf[WakeupJob])
            .withIdentity(jobKey)
            .usingJobData(WakeupJob.instanceIdKey, id.instanceId)
            .usingJobData(WakeupJob.templateIdKey, id.templateId)
            .build()
          scheduler.scheduleJob(jobDetail, java.util.Set.of(trigger), true)
        }
      case None          =>
        scheduler.deleteJob(jobKey)
        ()
    }
  }

  override def initialize(wakeUp: WorkflowInstanceId => IO[Unit]): IO[Unit] =
    IO.fromTry(scheduler.setWakeupContext(WakeupJob.Context(wakeUp, dispatcher)))

}
