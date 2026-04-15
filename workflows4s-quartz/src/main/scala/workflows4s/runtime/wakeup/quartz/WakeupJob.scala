package workflows4s.runtime.wakeup.quartz

import scala.util.{Failure, Success, Try}
import org.quartz.{Job, JobExecutionContext, Scheduler}
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.quartz.WakeupJob.{instanceIdKey, templateIdKey, wakeupContextsKey}

class WakeupJob extends Job {
  override def execute(context: JobExecutionContext): Unit = {
    val id         = context.getJobDetail.getJobDataMap.getString(instanceIdKey)
    val templateId = context.getJobDetail.getJobDataMap.getString(templateIdKey)
    val wakeupCtx  = context.getScheduler.getWakeupContext
    wakeupCtx.get.run(WorkflowInstanceId(templateId, id))
  }
}

object WakeupJob {
  val wakeupContextsKey = "workflows4s-wakeups"
  val instanceIdKey     = "instance-id"
  val templateIdKey     = "template-id"

  /** F-agnostic wakeup callback. The effect type is hidden behind a synchronous `run` that the job driver invokes; concrete implementations bridge
    * their `F[Unit]` via e.g. a cats-effect `Dispatcher`.
    */
  case class Context(run: WorkflowInstanceId => Unit)

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
