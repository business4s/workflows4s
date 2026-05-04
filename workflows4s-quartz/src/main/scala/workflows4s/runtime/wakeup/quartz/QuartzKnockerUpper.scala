package workflows4s.runtime.wakeup.quartz

import cats.effect.Async
import cats.effect.std.Dispatcher
import org.quartz.*
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant
import java.util.Date

/** [[workflows4s.runtime.wakeup.KnockerUpper]] implementation backed by a Quartz `Scheduler`. The `dispatcher` is used to bridge Quartz's synchronous
  * `Job.execute` callback into `F`.
  */
class QuartzKnockerUpper[F[_]: Async](scheduler: Scheduler, dispatcher: Dispatcher[F])
    extends KnockerUpper.Agent[F]
    with KnockerUpper.Process[F, F[Unit]] {
  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = Async[F].delay {
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

  override def initialize(wakeUp: WorkflowInstanceId => F[Unit]): F[Unit] =
    Async[F].fromTry(scheduler.setWakeupContext(WakeupJob.Context(id => dispatcher.unsafeRunSync(wakeUp(id)))))

}
