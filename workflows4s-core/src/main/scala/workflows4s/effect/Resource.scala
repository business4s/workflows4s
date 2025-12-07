package workflows4s.effect

/** A resource that is acquired and released safely.
  *
  * Ensures that the release action runs even if the usage fails.
  */
trait Resource[F[_], +A] { self =>

  /** Uses the resource, ensuring proper cleanup. */
  def use[B](f: A => F[B]): F[B]

  /** Transforms the resource value. */
  def map[B](f: A => B)(using E: Effect[F]): Resource[F, B] =
    Resource.make(
      E.map(use(a => E.pure((a, f(a)))))(_._2),
    )(_ => E.unit)

  /** Chains resource acquisition. */
  def flatMap[B](f: A => Resource[F, B]): Resource[F, B] =
    new Resource[F, B] {
      def use[C](g: B => F[C]): F[C] =
        self.use(a => f(a).use(g))
    }
}

object Resource {

  /** Creates a resource from acquire and release actions. */
  def make[F[_], A](acquire: F[A])(release: A => F[Unit])(using E: Effect[F]): Resource[F, A] =
    new Resource[F, A] {
      def use[B](f: A => F[B]): F[B] =
        E.flatMap(acquire) { a =>
          E.flatMap(
            E.handleErrorWith(f(a))(err => E.flatMap(release(a))(_ => E.raiseError(err))),
          )(b => E.as(release(a), b))
        }
    }

  /** Creates a resource that doesn't need cleanup. */
  def pure[F[_], A](a: A): Resource[F, A] =
    new Resource[F, A] {
      def use[B](f: A => F[B]): F[B] = f(a)
    }

  /** Creates a resource from an effectful acquisition with no cleanup. */
  def eval[F[_], A](fa: F[A])(using E: Effect[F]): Resource[F, A] =
    make(fa)(_ => E.unit)

  /** Combines two resources. */
  def both[F[_], A, B](ra: Resource[F, A], rb: Resource[F, B])(using E: Effect[F]): Resource[F, (A, B)] =
    ra.flatMap(a => rb.map(b => (a, b)))
}
