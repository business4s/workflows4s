package workflows4s.runtime.wakeup

import workflows4s.effect.Effect

import java.time.Instant
import workflows4s.runtime.WorkflowInstanceId

object NoOpKnockerUpper {

  def agent[F[_]: Effect]: KnockerUpper.Agent[F] = new KnockerUpper.Agent[F] {
    override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = Effect[F].unit
  }
}
