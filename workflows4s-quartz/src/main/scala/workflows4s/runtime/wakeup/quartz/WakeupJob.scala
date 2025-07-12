package workflows4s.runtime.wakeup.quartz

import scala.util.{Failure, Success, Try}
import cats.effect.IO
import cats.effect.std.Dispatcher
import org.quartz.{Job, JobExecutionContext, Scheduler}
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.quartz.WakeupJob.{runtimeIdKey, wakeupContextsKey, workflowIdKey}

class WakeupJob extends Job {
  override def execute(context: JobExecutionContext): Unit = {
    val id        = context.getJobDetail.getJobDataMap.getString(workflowIdKey)
    val runtimeId = context.getJobDetail.getJobDataMap.getString(runtimeIdKey)
    val wakeupCtx = context.getScheduler.getWakeupContext
    wakeup(WorkflowInstanceId(runtimeId, id), wakeupCtx.get)
  }

  private def wakeup(id: WorkflowInstanceId, ctx: WakeupJob.Context): Unit = {
    ctx.dispatcher.unsafeRunSync(for {
      _ <- ctx.wakeup(id)
    } yield ())
  }
}

object WakeupJob {
  val wakeupContextsKey = "workflows4s-wakeups"
  val workflowIdKey     = "workflows-id"
  val runtimeIdKey      = "runtime-id"

  case class Context(wakeup: WorkflowInstanceId => IO[Unit], dispatcher: Dispatcher[IO])

}

extension (scheduler: Scheduler) {

  def getWakeupContext: Try[WakeupJob.Context] = {
    Option(scheduler.getContext.get(wakeupContextsKey))
      .map(_.asInstanceOf[WakeupJob.Context]) match {
      case Some(ctx) => Success(ctx)
      case None      => Failure(new RuntimeException(s"No wakeup context available"))
    }
  }

  def setWakeupContext(ctx: WakeupJob.Context): Try[Unit] = {
    val ctxOpt = Option(scheduler.getContext.get(wakeupContextsKey))
      .map(_.asInstanceOf[WakeupJob.Context])

    ctxOpt match {
      case Some(_) => Failure(new RuntimeException(s"Wakeup context already set"))
      case None    =>
        scheduler.getContext.put(wakeupContextsKey, ctx)
        Success(())
    }
  }

}
