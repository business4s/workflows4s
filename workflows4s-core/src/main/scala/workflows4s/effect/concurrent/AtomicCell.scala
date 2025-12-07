package workflows4s.effect.concurrent

/** An atomic cell that provides effectful updates with mutual exclusion.
  *
  * Unlike Ref, AtomicCell allows effectful update functions, ensuring that only one effect runs at a time.
  */
trait AtomicCell[F[_], A] {

  /** Gets the current value. */
  def get: F[A]

  /** Sets the value to `a`. */
  def set(a: A): F[Unit]

  /** Atomically modifies the value using an effectful function. */
  def evalUpdate(f: A => F[A]): F[Unit]

  /** Atomically modifies the value using an effectful function and returns a result. */
  def evalModify[B](f: A => F[(A, B)]): F[B]

  /** Atomically modifies the value using a pure function. */
  def update(f: A => A): F[Unit]

  /** Gets the current value and then sets it to the result of applying `f`. */
  def getAndUpdate(f: A => A): F[A]

  /** Applies `f` to the current value and then gets the new value. */
  def updateAndGet(f: A => A): F[A]
}

object AtomicCell {

  /** Factory for creating AtomicCell instances. */
  trait Make[F[_]] {

    /** Creates a new AtomicCell initialized with the given value. */
    def of[A](initial: A): F[AtomicCell[F, A]]
  }

  object Make {
    def apply[F[_]](using m: Make[F]): Make[F] = m
  }
}
