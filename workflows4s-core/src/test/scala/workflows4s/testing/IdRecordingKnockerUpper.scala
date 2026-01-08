package workflows4s.testing

import cats.Id
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

/** Simple Id-based recording knocker upper for tests that don't use IO effect.
  */
class IdRecordingKnockerUpper extends KnockerUpper.Agent[Id] with StrictLogging {
  private var wakeups: Map[WorkflowInstanceId, Option[Instant]] = Map()

  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = wakeups.get(id).flatten

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): Id[Unit] = {
    logger.debug(s"Registering wakeup for $id at $at")
    this.wakeups = wakeups.updatedWith(id)(_ => Some(at))
  }
}
