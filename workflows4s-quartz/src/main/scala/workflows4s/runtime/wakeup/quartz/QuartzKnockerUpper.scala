package workflows4s.runtime.wakeup.quartz

import java.time.Instant
import java.util.Date

import cats.effect.IO
import cats.effect.std.Dispatcher
import org.quartz.*
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper.RuntimeId

class QuartzKnockerUpper[Id](runtimeId: RuntimeId, scheduler: Scheduler, dispatcher: Dispatcher[IO])(using
    idCodec: StringCodec[Id],
) extends KnockerUpper.Agent[Id]
    with KnockerUpper.Process[IO, Id, IO[Unit]] {
  override def updateWakeup(id: Id, at: Option[Instant]): IO[Unit] = IO {
    val jobKey = new JobKey(idCodec.encode(id))
    at match {
      case Some(instant) =>
        val trigger = TriggerBuilder
          .newTrigger()
          .withIdentity(idCodec.encode(id))
          .startAt(Date.from(instant))
          .build()

        if (scheduler.checkExists(jobKey)) {
          scheduler.rescheduleJob(trigger.getKey, trigger)
        } else {
          val jobDetail = JobBuilder
            .newJob(classOf[WakeupJob])
            .withIdentity(jobKey)
            .usingJobData(WakeupJob.workflowIdKey, idCodec.encode(id))
            .usingJobData(WakeupJob.runtimeIdKey, runtimeId)
            .build()
          scheduler.scheduleJob(jobDetail, trigger)
        }
      case None          => scheduler.deleteJob(jobKey)
    }
  }

  override def initialize(wakeUp: Id => IO[Unit]): IO[Unit] =
    IO.fromTry(scheduler.setWakeupContext(runtimeId, WakeupJob.Context(wakeUp, idCodec, dispatcher)))

}

object QuartzKnockerUpper {

  opaque type RuntimeId <: String = String
  object RuntimeId {
    def apply(value: String): RuntimeId = value
  }

}
