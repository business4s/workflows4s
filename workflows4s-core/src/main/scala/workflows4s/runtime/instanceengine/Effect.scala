package workflows4s.runtime.instanceengine

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/** Outcome of an effect execution, used for guaranteeCase */
enum Outcome[+A] {
  case Succeeded(value: A)
  case Errored(error: Throwable)
  case Canceled
}

/** A mutable reference that can be atomically updated */
trait Ref[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def update(f: A => A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]
  def getAndUpdate(f: A => A): F[A]
}

/** A fiber/background computation that can be canceled */
trait Fiber[F[_], A] {
  def cancel: F[Unit]
  def join: F[Outcome[A]]
}

trait Effect[F[_]] {

  // Mutex type for effect-polymorphic locking
  type Mutex

  /** Create a new mutex within the effect context */
  def createMutex: F[Mutex]

  /** Run an effect while holding the mutex, ensuring release on completion/error. Note: fa is by-name to ensure the effect is not started until the
    * lock is acquired. This is critical for eager effect types like Future.
    */
  def withLock[A](m: Mutex)(fa: => F[A]): F[A]

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

  // Suspension of side effects
  def delay[A](a: => A): F[A]

  // Concurrency primitives
  /** Create a new mutable reference */
  def ref[A](initial: A): F[Ref[F, A]]

  /** Start an effect in the background, returning a fiber that can be canceled */
  def start[A](fa: F[A]): F[Fiber[F, A]]

  /** Run an effect with a finalizer that depends on the outcome */
  def guaranteeCase[A](fa: F[A])(finalizer: Outcome[A] => F[Unit]): F[A]

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

  /** Run the effect synchronously and return the result. This is unsafe because it blocks the current thread and may throw exceptions. Use only in
    * tests or when you're certain the effect will complete quickly.
    */
  def runSyncUnsafe[A](fa: F[A]): A
}

object Effect {

  def apply[F[_]](using e: Effect[F]): Effect[F] = e

  /** Effect instance for cats.Id (synchronous, blocking execution). Errors are thrown as exceptions.
    */
  given idEffect: Effect[cats.Id] = new Effect[cats.Id] {
    type Mutex = java.util.concurrent.Semaphore

    def createMutex: cats.Id[Mutex] = new java.util.concurrent.Semaphore(1)

    def withLock[A](m: Mutex)(fa: => cats.Id[A]): cats.Id[A] = {
      m.acquire()
      try fa
      finally m.release()
    }

    def pure[A](a: A): cats.Id[A]                                                     = a
    def flatMap[A, B](fa: cats.Id[A])(f: A => cats.Id[B]): cats.Id[B]                 = f(fa)
    def map[A, B](fa: cats.Id[A])(f: A => B): cats.Id[B]                              = f(fa)
    def raiseError[A](e: Throwable): cats.Id[A]                                       = throw e
    def handleErrorWith[A](fa: => cats.Id[A])(f: Throwable => cats.Id[A]): cats.Id[A] =
      try fa
      catch { case e: Throwable => f(e) }
    def sleep(duration: scala.concurrent.duration.FiniteDuration): cats.Id[Unit]      =
      Thread.sleep(duration.toMillis)
    def delay[A](a: => A): cats.Id[A]                                                 = a

    def ref[A](initial: A): cats.Id[Ref[cats.Id, A]] = new Ref[cats.Id, A] {
      // AtomicReference is thread-safe, no need for extra synchronization for Id
      private val underlying = new AtomicReference[A](initial)

      def get: cats.Id[A]          = underlying.get()
      def set(a: A): cats.Id[Unit] = underlying.set(a)

      def update(f: A => A): cats.Id[Unit] = {
        underlying.updateAndGet(x => f(x))
        ()
      }

      def modify[B](f: A => (A, B)): cats.Id[B] = {
        var res: B = null.asInstanceOf[B]
        underlying.updateAndGet { current =>
          val (next, b) = f(current)
          res = b
          next
        }
        res
      }

      def getAndUpdate(f: A => A): cats.Id[A] = underlying.getAndUpdate(x => f(x))
    }

    def start[A](fa: cats.Id[A]): cats.Id[Fiber[cats.Id, A]] = {
      // For Id, we execute immediately and return a completed fiber
      val result =
        try Outcome.Succeeded(fa)
        catch { case e: Throwable => Outcome.Errored(e) }
      new Fiber[cats.Id, A] {
        def cancel: cats.Id[Unit]     = ()
        def join: cats.Id[Outcome[A]] = result
      }
    }

    def guaranteeCase[A](fa: cats.Id[A])(finalizer: Outcome[A] => cats.Id[Unit]): cats.Id[A] = {
      val outcome =
        try Outcome.Succeeded(fa)
        catch { case e: Throwable => Outcome.Errored(e) }
      finalizer(outcome)
      outcome match {
        case Outcome.Succeeded(a) => a
        case Outcome.Errored(e)   => throw e
        case Outcome.Canceled     => throw new InterruptedException("Canceled")
      }
    }

    def runSyncUnsafe[A](fa: cats.Id[A]): A = fa
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

}
