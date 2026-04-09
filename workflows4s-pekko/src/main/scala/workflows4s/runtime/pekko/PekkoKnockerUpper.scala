package workflows4s.runtime.pekko

import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.{Cancellable, Scheduler as ClassicScheduler}
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters.JavaDurationOps

/** Pekko-native KnockerUpper backed by [[Scheduler.scheduleOnce]] */
class PekkoKnockerUpper(scheduler: ClassicScheduler)(using ec: ExecutionContext)
    extends KnockerUpper.Agent[Future]
    with KnockerUpper.Process[Future, Unit]
    with StrictLogging {

  private val scheduled                                                 = new ConcurrentHashMap[WorkflowInstanceId, Cancellable]()
  @volatile private var wakeupLogic: WorkflowInstanceId => Future[Unit] = scala.compiletime.uninitialized

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): Future[Unit] = {
    at match {
      case Some(wakeupTime) =>
        cancelExisting(id)
        val now         = Instant.now()
        val delay       = Duration.between(now, wakeupTime).toScala
        logger.debug(s"Scheduling wakeup for $id in $delay")
        val cancellable = scheduler.scheduleOnce(
          delay,
          () => {
            logger.debug(s"Waking up $id")
            val _ = scheduled.remove(id)
            val _ = wakeupLogic(id)
          },
        )
        scheduled.put(id, cancellable)
        Future.successful(())
      case None             =>
        cancelExisting(id)
        Future.successful(())
    }
  }

  override def initialize(wakeUp: WorkflowInstanceId => Future[Unit]): Unit = {
    if wakeupLogic != null then throw new IllegalStateException("initialize can be called only once")
    wakeupLogic = wakeUp
  }

  /** Cancels all pending wakeups. */
  def shutdown(): Unit = {
    scheduled.forEach((_, c) => { val _ = c.cancel() })
    scheduled.clear()
  }

  private def cancelExisting(id: WorkflowInstanceId): Unit = {
    val prev = scheduled.remove(id)
    if prev != null then { val _ = prev.cancel() }
  }

}

object PekkoKnockerUpper {

  def create(using system: ActorSystem[?]): PekkoKnockerUpper = {
    given ExecutionContext = system.executionContext
    new PekkoKnockerUpper(system.classicSystem.scheduler)
  }

}
