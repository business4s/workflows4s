package workflows4s.testing

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

class RecordingKnockerUpper extends KnockerUpper.Agent, StrictLogging {

  private var wakeups: Map[WorkflowInstanceId, Option[Instant]]     = Map()
  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = wakeups.get(id).flatten

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = IO {
    logger.debug(s"Registering wakeup for $id at $at")
    this.wakeups = wakeups.updatedWith(id)(_ => Some(at))
  }
}
