package workflows4s.testing

import cats.Monad
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.internal.WeakSync

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class RecordingKnockerUpper[F[_]: Monad] extends KnockerUpper.Agent[F], StrictLogging {

  private val wakeups: ConcurrentHashMap[WorkflowInstanceId, Option[Instant]] = new ConcurrentHashMap()
  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant]           = Option(wakeups.get(id)).flatten

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = WeakSync.delay[F] {
    logger.debug(s"Registering wakeup for $id at $at")
    wakeups.put(id, at): Unit
  }
}
