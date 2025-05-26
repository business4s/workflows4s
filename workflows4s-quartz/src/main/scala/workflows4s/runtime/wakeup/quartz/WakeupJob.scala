package workflows4s.runtime.wakeup.quartz

import scala.util.{Failure, Success, Try}

import cats.effect.IO
import cats.effect.std.Dispatcher
import org.quartz.{Job, JobExecutionContext, Scheduler}
import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper.RuntimeId
import workflows4s.runtime.wakeup.quartz.WakeupJob.{runtimeIdKey, wakeupContextsKey, workflowIdKey}

class WakeupJob extends Job {
  override def execute(context: JobExecutionContext): Unit = {
    val id        = context.getJobDetail.getJobDataMap.getString(workflowIdKey)
    val runtimeId = context.getJobDetail.getJobDataMap.getString(runtimeIdKey)
    val wakeupCtx = context.getScheduler.getWakeupContext(RuntimeId(runtimeId))
    wakeup(id, wakeupCtx.get)
  }

  private def wakeup[T](id: String, ctx: WakeupJob.Context[T]): Unit = {
    ctx.dispatcher.unsafeRunSync(for {
      _ <- ctx.wakeup(ctx.idCodec.decode(id))
    } yield ())
  }
}

object WakeupJob {
  val wakeupContextsKey = "workflows4s-wakeups"
  val workflowIdKey     = "workflows-id"
  val runtimeIdKey      = "runtime-id"

  case class Context[Id](wakeup: Id => IO[Unit], idCodec: StringCodec[Id], dispatcher: Dispatcher[IO])

}

extension (scheduler: Scheduler) {

  def getWakeupContext(runtimeId: RuntimeId): Try[WakeupJob.Context[?]] = {
    Option(scheduler.getContext.get(wakeupContextsKey))
      .map(_.asInstanceOf[Map[String, WakeupJob.Context[?]]])
      .flatMap(_.get(runtimeId)) match {
      case Some(ctx) => Success(ctx)
      case None      => Failure(new RuntimeException(s"No wakeup context for runtime $runtimeId"))
    }
  }

  def setWakeupContext(runtimeId: RuntimeId, ctx: WakeupJob.Context[?]): Try[Unit] = {
    val map = Option(scheduler.getContext.get(wakeupContextsKey))
      .map(_.asInstanceOf[Map[String, WakeupJob.Context[?]]])
      .getOrElse(Map())
    if map.contains(runtimeId) then Failure(new RuntimeException(s"Wakeup context for runtime $runtimeId already set"))
    else {
      scheduler.getContext.put(wakeupContextsKey, map.updated(runtimeId, ctx))
      Success(())
    }
  }

}
