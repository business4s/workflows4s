package workflows4s.effect

import cats.effect.IO
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Core effect type class for workflows4s.
  *
  * Provides the minimal set of operations needed to execute workflows in an effect-polymorphic way. Implementations
  * exist for ZIO, Cats Effect, OX, and other effect systems.
  *
  * The key feature is `liftIO` which allows converting the internal IO-based operations to the target effect type.
  * This enables workflows4s-core to use IO internally while runtimes work with any effect type.
  */
trait Effect[F[_]] {

  // Core monadic operations
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]

  // Error handling
  def raiseError[A](e: Throwable): F[A]
  def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A]

  // Time operations
  def sleep(duration: FiniteDuration): F[Unit]
  def realTimeInstant: F[Instant]

  // Suspension of side effects
  def delay[A](a: => A): F[A]

  /** Convert a cats.effect.IO to this effect type.
    *
    * This is the key operation that allows workflows4s-core to use IO internally
    * while runtimes can work with any effect type (ZIO, Monix, etc.)
    */
  def liftIO[A](io: IO[A]): F[A]

  // Derived operations with default implementations
  def void[A](fa: F[A]): F[Unit] = map(fa)(_ => ())

  def as[A, B](fa: F[A], b: B): F[B] = map(fa)(_ => b)

  def whenA(cond: Boolean)(fa: => F[Unit]): F[Unit] =
    if (cond) fa else unit

  def unit: F[Unit] = pure(())

  def traverse[A, B](as: List[A])(f: A => F[B]): F[List[B]] =
    as.foldRight(pure(List.empty[B])) { (a, acc) =>
      flatMap(f(a))(b => map(acc)(bs => b :: bs))
    }

  def traverse_[A, B](as: List[A])(f: A => F[B]): F[Unit] =
    as.foldLeft(unit)((acc, a) => flatMap(acc)(_ => void(f(a))))

  def sequence[A](fas: List[F[A]]): F[List[A]] = traverse(fas)(identity)

  def sequence_[A](fas: List[F[A]]): F[Unit] = traverse_(fas)(identity)

  def fromOption[A](opt: Option[A], ifNone: => Throwable): F[A] =
    opt match {
      case Some(a) => pure(a)
      case None    => raiseError(ifNone)
    }

  def fromEither[A](either: Either[Throwable, A]): F[A] =
    either match {
      case Right(a) => pure(a)
      case Left(e)  => raiseError(e)
    }

  def attempt[A](fa: F[A]): F[Either[Throwable, A]] =
    handleErrorWith(map(fa)(Right(_): Either[Throwable, A]))(e => pure(Left(e)))

  def productR[A, B](fa: F[A], fb: F[B]): F[B] =
    flatMap(fa)(_ => fb)

  def productL[A, B](fa: F[A], fb: F[B]): F[A] =
    flatMap(fa)(a => map(fb)(_ => a))
}

object Effect {

  def apply[F[_]](using e: Effect[F]): Effect[F] = e

  // Syntax extensions
  extension [F[_], A](fa: F[A])(using E: Effect[F]) {
    def map[B](f: A => B): F[B]                      = E.map(fa)(f)
    def flatMap[B](f: A => F[B]): F[B]               = E.flatMap(fa)(f)
    def handleErrorWith(f: Throwable => F[A]): F[A]  = E.handleErrorWith(fa)(f)
    def void: F[Unit]                                = E.void(fa)
    def as[B](b: B): F[B]                            = E.as(fa, b)
    def attempt: F[Either[Throwable, A]]             = E.attempt(fa)
    def *>[B](fb: F[B]): F[B]                        = E.productR(fa, fb)
    def <*[B](fb: F[B]): F[A]                        = E.productL(fa, fb)
  }

  extension [A](a: A) {
    def pure[F[_]](using E: Effect[F]): F[A] = E.pure(a)
  }

  extension [A](opt: Option[A]) {
    def liftTo[F[_]](ifNone: => Throwable)(using E: Effect[F]): F[A] =
      E.fromOption(opt, ifNone)
  }

  extension [A](either: Either[Throwable, A]) {
    def liftTo[F[_]](using E: Effect[F]): F[A] =
      E.fromEither(either)
  }

  extension [A](io: IO[A]) {
    def liftTo[F[_]](using E: Effect[F]): F[A] =
      E.liftIO(io)
  }
}
