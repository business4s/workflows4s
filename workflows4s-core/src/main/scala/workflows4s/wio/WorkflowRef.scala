package workflows4s.wio

/** Abstract reference type that works across all effect systems. The actual implementation is provided by each effect system's module (e.g., CatsRef
  * for Cats Effect, ZIORef for ZIO).
  */
trait WorkflowRef[F[_], A] {

  /** Get the current value */
  def get: F[A]

  /** Update the value using a function */
  def update(f: A => A): F[Unit]

  /** Modify the value and return a result */
  def modify[B](f: A => (A, B)): F[B]
}

object WorkflowRef {

  /** Factory for creating WorkflowRef instances. Each effect system provides its own implementation.
    */
  trait Factory[F[_]] {
    def create[A](initial: A): F[WorkflowRef[F, A]]
  }
}
