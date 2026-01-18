package workflows4s.runtime.wakeup

import java.time.Instant
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect

object NoOpKnockerUpper {

  def agent[F[_]](using E: Effect[F]): KnockerUpper.Agent[F] =
    new KnockerUpper.Agent[F] {
      override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = E.unit
    }
}
