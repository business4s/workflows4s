package workflows4s.effect.concurrent

import workflows4s.effect.Resource

/** A semaphore for controlling concurrent access.
  *
  * Provides mutual exclusion when permits = 1, or bounded concurrency for higher values.
  */
trait Semaphore[F[_]] {

  /** Acquires a single permit, blocking until one is available. */
  def acquire: F[Unit]

  /** Releases a single permit. */
  def release: F[Unit]

  /** Returns the number of currently available permits. */
  def available: F[Long]

  /** Acquires a permit and returns a Resource that releases it when done. */
  def permit: Resource[F, Unit]
}

object Semaphore {

  /** Factory for creating Semaphore instances. */
  trait Make[F[_]] {

    /** Creates a new Semaphore with the specified number of permits. */
    def apply(permits: Long): F[Semaphore[F]]
  }

  object Make {
    def apply[F[_]](using m: Make[F]): Make[F] = m
  }
}
