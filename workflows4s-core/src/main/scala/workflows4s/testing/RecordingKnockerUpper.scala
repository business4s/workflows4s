package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.{Effect, Ref}
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

/** Generic test utility that records wakeup registrations for verification in tests.
  *
  * Uses Ref[F, Map] for thread-safe storage compatible with any effect type F[_].
  *
  * @tparam F
  *   The effect type (e.g., IO, LazyFuture, Future, Id)
  */
class RecordingKnockerUpper[F[_]](
    storage: Ref[F, Map[WorkflowInstanceId, Instant]],
)(using E: Effect[F])
    extends KnockerUpper.Agent[F]
    with StrictLogging {

  /** Retrieve the last registered wakeup time for a specific instance (effectful). */
  def lastRegisteredWakeup(id: WorkflowInstanceId): F[Option[Instant]] = {
    E.map(storage.get)(_.get(id))
  }

  /** Retrieve the last registered wakeup time for a specific instance (synchronous, uses runSyncUnsafe). Use this in test assertions for convenience.
    */
  def lastRegisteredWakeupUnsafe(id: WorkflowInstanceId): Option[Instant] = {
    E.runSyncUnsafe(lastRegisteredWakeup(id))
  }

  override def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit] = {
    at match {
      case Some(instant) =>
        E.flatMap(E.delay(logger.debug(s"Registering wakeup for $id at $at")))(_ => storage.update(_ + (id -> instant)))
      case None          =>
        E.flatMap(E.delay(logger.debug(s"Removing wakeup for $id")))(_ => storage.update(_ - id))
    }
  }
}

object RecordingKnockerUpper {
  def apply[F[_]](using E: Effect[F]): F[RecordingKnockerUpper[F]] =
    E.map(E.ref(Map.empty[WorkflowInstanceId, Instant]))(new RecordingKnockerUpper(_))
}
