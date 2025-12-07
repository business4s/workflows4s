package workflows4s.effect.concurrent

/** A single-assignment variable.
  *
  * Can only be completed once, and callers of `get` will block until it's completed.
  */
trait Deferred[F[_], A] {

  /** Gets the value, blocking until it's available. */
  def get: F[A]

  /** Attempts to complete with the given value. Returns true if successful, false if already completed. */
  def complete(a: A): F[Boolean]

  /** Attempts to get the value without blocking. Returns None if not yet completed. */
  def tryGet: F[Option[A]]
}

object Deferred {

  /** Factory for creating Deferred instances. */
  trait Make[F[_]] {

    /** Creates a new uncompleted Deferred. */
    def apply[A]: F[Deferred[F, A]]
  }

  object Make {
    def apply[F[_]](using m: Make[F]): Make[F] = m
  }
}
