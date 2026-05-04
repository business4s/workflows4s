package workflows4s.runtime.wakeup

import cats.Applicative

import java.time.Instant
import workflows4s.runtime.WorkflowInstanceId

object NoOpKnockerUpper {

  def agent[F[_]: Applicative]: KnockerUpper.Agent[F] = new KnockerUpper.Agent[F] {
    override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = Applicative[F].unit
  }
}
