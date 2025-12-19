package workflows4s.testing

import cats.Id
import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

// IO-based implementation that can also provide an Id view
class RecordingKnockerUpper extends KnockerUpper.Agent[IO] with StrictLogging {
  private var wakeups: Map[WorkflowInstanceId, Option[Instant]] = Map()

  def lastRegisteredWakeup(id: WorkflowInstanceId): Option[Instant] = wakeups.get(id).flatten

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = IO {
    logger.debug(s"Registering wakeup for $id at $at")
    this.wakeups = wakeups.updatedWith(id)(_ => Some(at))
  }

  // Create an Id-based view of this same instance (shares state)
  lazy val asId: KnockerUpper.Agent[Id] = new KnockerUpper.Agent[Id] {
    override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): Id[Unit] = {
      logger.debug(s"Registering wakeup for $id at $at")
      RecordingKnockerUpper.this.wakeups = RecordingKnockerUpper.this.wakeups.updatedWith(id)(_ => Some(at))
    }
  }
}

object RecordingKnockerUpper {
  def apply(): RecordingKnockerUpper = new RecordingKnockerUpper()
}
