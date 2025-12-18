package workflows4s.runtime.wakeup

import java.time.Instant
import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId

object NoOpKnockerUpper {

  object Agent extends KnockerUpper.Agent[IO] {
    override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit] = IO.unit
  }
}
