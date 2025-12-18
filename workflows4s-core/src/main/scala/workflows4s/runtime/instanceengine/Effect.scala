package workflows4s.runtime.instanceengine

import workflows4s.wio.{WorkflowRef, WorkflowResult}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

trait Effect[F[_]] {

  // Core monadic operations
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]

  // Error handling
  def raiseError[A](e: Throwable): F[A]
  // Note: fa is by-name to allow catching errors from synchronous effects like Id
  def handleErrorWith[A](fa: => F[A])(f: Throwable => F[A]): F[A]

  // Time operations
  def sleep(duration: FiniteDuration): F[Unit]
  def realTimeInstant: F[Instant]

  // Suspension of side effects
  def delay[A](a: => A): F[A]

  // Derived operations with default implementations
  def void[A](fa: F[A]): F[Unit] = map(fa)(_ => ())

  def as[A, B](fa: F[A], b: B): F[B] = map(fa)(_ => b)

  def whenA(cond: Boolean)(fa: => F[Unit]): F[Unit] =
    if cond then fa else unit

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

  def onError[A](fa: F[A])(f: Throwable => F[Unit]): F[A] =
    handleErrorWith(fa) { e =>
      flatMap(f(e))(_ => raiseError(e))
    }

  def guarantee[A](fa: F[A], finalizer: F[Unit]): F[A] =
    flatMap(attempt(fa)) {
      case Right(a) => map(finalizer)(_ => a)
      case Left(e)  => flatMap(finalizer)(_ => raiseError(e))
    }

  /** Optional factory for creating WorkflowRef instances. Override in effect-specific implementations.
    */
  def refFactory: Option[WorkflowRef.Factory[F]] = None

  /** Interpret a WorkflowResult into this effect type.
    */
  def interpret[A](result: WorkflowResult[A]): F[A] = {
    result match {
      case WorkflowResult.Pure(value)                      => pure(value.asInstanceOf[A])
      case WorkflowResult.Fail(error)                      => raiseError(error)
      case WorkflowResult.Defer(thunk)                     => delay(thunk().asInstanceOf[A])
      case fm: WorkflowResult.FlatMap[b, A] @unchecked     =>
        flatMap(interpret(fm.base))(a => interpret(fm.f(a)))
      case he: WorkflowResult.HandleError[b, A] @unchecked =>
        handleErrorWith(interpret(he.base).asInstanceOf[F[A]])(e => interpret(he.handler(e))).asInstanceOf[F[A]]
      case WorkflowResult.RealTime                         => realTimeInstant.asInstanceOf[F[A]]
    }
  }
}

object Effect {

  def apply[F[_]](using e: Effect[F]): Effect[F] = e

  /** Effect instance for cats.Id (synchronous, blocking execution). Errors are thrown as exceptions.
    */
  given idEffect: Effect[cats.Id] = new Effect[cats.Id] {
    def pure[A](a: A): cats.Id[A]                                                     = a
    def flatMap[A, B](fa: cats.Id[A])(f: A => cats.Id[B]): cats.Id[B]                 = f(fa)
    def map[A, B](fa: cats.Id[A])(f: A => B): cats.Id[B]                              = f(fa)
    def raiseError[A](e: Throwable): cats.Id[A]                                       = throw e
    def handleErrorWith[A](fa: => cats.Id[A])(f: Throwable => cats.Id[A]): cats.Id[A] =
      try fa
      catch { case e: Throwable => f(e) }
    def sleep(duration: scala.concurrent.duration.FiniteDuration): cats.Id[Unit]      =
      Thread.sleep(duration.toMillis)
    def realTimeInstant: cats.Id[java.time.Instant]                                   = java.time.Instant.now()
    def delay[A](a: => A): cats.Id[A]                                                 = a
  }

  /** Effect instance for scala.concurrent.Future (asynchronous execution).
    */
  def futureEffect(using ec: scala.concurrent.ExecutionContext): Effect[scala.concurrent.Future] =
    new Effect[scala.concurrent.Future] {
      import scala.concurrent.{Future, blocking}

      def pure[A](a: A): Future[A]                                                   = Future.successful(a)
      def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B]                 = fa.flatMap(f)
      def map[A, B](fa: Future[A])(f: A => B): Future[B]                             = fa.map(f)
      def raiseError[A](e: Throwable): Future[A]                                     = Future.failed(e)
      def handleErrorWith[A](fa: => Future[A])(f: Throwable => Future[A]): Future[A] =
        fa.recoverWith { case e => f(e) }
      def sleep(duration: scala.concurrent.duration.FiniteDuration): Future[Unit]    =
        Future(blocking(Thread.sleep(duration.toMillis)))
      def realTimeInstant: Future[java.time.Instant]                                 = Future.successful(java.time.Instant.now())
      def delay[A](a: => A): Future[A]                                               = Future(a)
    }

  /** Effect instance for cats.effect.IO (asynchronous, non-blocking execution). TODO: Move to workflows4s-cats module
    */
  given ioEffect: Effect[cats.effect.IO] = new Effect[cats.effect.IO] {
    import cats.effect.IO

    def pure[A](a: A): IO[A]                                                = IO.pure(a)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]                      = fa.flatMap(f)
    def map[A, B](fa: IO[A])(f: A => B): IO[B]                              = fa.map(f)
    def raiseError[A](e: Throwable): IO[A]                                  = IO.raiseError(e)
    def handleErrorWith[A](fa: => IO[A])(f: Throwable => IO[A]): IO[A]      = fa.handleErrorWith(f)
    def sleep(duration: scala.concurrent.duration.FiniteDuration): IO[Unit] = IO.sleep(duration)
    def realTimeInstant: IO[java.time.Instant]                              = IO.realTimeInstant
    def delay[A](a: => A): IO[A]                                            = IO.delay(a)
  }

  // Syntax extensions
  // Note: Using by-name fa for handleErrorWith to match the trait signature
  extension [F[_], A](fa: => F[A])(using E: Effect[F]) {
    def map[B](f: A => B): F[B]                     = E.map(fa)(f)
    def flatMap[B](f: A => F[B]): F[B]              = E.flatMap(fa)(f)
    def handleErrorWith(f: Throwable => F[A]): F[A] = E.handleErrorWith(fa)(f)
    def void: F[Unit]                               = E.void(fa)
    def as[B](b: B): F[B]                           = E.as(fa, b)
    def attempt: F[Either[Throwable, A]]            = E.attempt(fa)
    def *>[B](fb: F[B]): F[B]                       = E.productR(fa, fb)
    def <*[B](fb: F[B]): F[A]                       = E.productL(fa, fb)

    def onError(f: Throwable => F[Unit]): F[A] = E.onError(fa)(f)

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
}
