package workflows4s.effect.concurrent

/** A mutable reference that provides atomic updates.
  *
  * This is an abstraction over effect-specific implementations like cats.effect.Ref or zio.Ref.
  */
trait Ref[F[_], A] {

  /** Gets the current value. */
  def get: F[A]

  /** Sets the value to `a`. */
  def set(a: A): F[Unit]

  /** Atomically modifies the value using the given function. */
  def update(f: A => A): F[Unit]

  /** Atomically modifies the value and returns a result. */
  def modify[B](f: A => (A, B)): F[B]

  /** Gets the current value and then sets it to the result of applying `f`. */
  def getAndUpdate(f: A => A): F[A]

  /** Applies `f` to the current value and then gets the new value. */
  def updateAndGet(f: A => A): F[A]

  /** Gets the current value and then sets it to `a`. */
  def getAndSet(a: A): F[A]
}

object Ref {

  /** Factory for creating Ref instances. */
  trait Make[F[_]] {

    /** Creates a new Ref initialized with the given value. */
    def of[A](initial: A): F[Ref[F, A]]
  }

  object Make {
    def apply[F[_]](using m: Make[F]): Make[F] = m
  }
}
