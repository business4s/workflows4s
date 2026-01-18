package workflows4s.testing

import workflows4s.runtime.WorkflowInstanceId

import java.time.Instant
import workflows4s.runtime.instanceengine.{Effect, Ref}
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.runtime.wakeup.KnockerUpper

class TestKnockerUpper[F[_]](
    storage: Ref[F, Map[WorkflowInstanceId, Instant]],
)(using E: Effect[F])
    extends KnockerUpper.Agent[F] {

  /** Retrieve the last requested wakeup time for a specific instance. */
  def lastRegisteredWakeup(id: WorkflowInstanceId): F[Option[Instant]] = {
    storage.get.map(_.get(id))
  }

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = {
    at match {
      case Some(instant) => storage.update(_ + (id -> instant))
      case None          => storage.update(_ - id)
    }
  }
}

object TestKnockerUpper {
  def create[F[_]](using E: Effect[F]): F[TestKnockerUpper[F]] =
    E.ref(Map.empty[WorkflowInstanceId, Instant]).map(new TestKnockerUpper(_))
}
