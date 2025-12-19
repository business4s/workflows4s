package workflows4s.runtime.instanceengine

import workflows4s.wio.{WorkflowRef, WorkflowResult}

import java.time.Instant
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

  /** Create a new mutex */
  def createMutex: Mutex

  /** Run an effect while holding the mutex, ensuring release on completion/error */
  def withLock[A](m: Mutex)(fa: F[A]): F[A]

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
    type Mutex = java.util.concurrent.Semaphore

    def createMutex: Mutex = new java.util.concurrent.Semaphore(1)

    def withLock[A](m: Mutex)(fa: cats.Id[A]): cats.Id[A] = {
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
    def realTimeInstant: cats.Id[java.time.Instant]                                   = java.time.Instant.now()
    def delay[A](a: => A): cats.Id[A]                                                 = a

    def ref[A](initial: A): cats.Id[Ref[cats.Id, A]] = new Ref[cats.Id, A] {
      @volatile private var value: A            = initial
      def get: cats.Id[A]                       = value
      def set(a: A): cats.Id[Unit]              = { value = a }
      def update(f: A => A): cats.Id[Unit]      = { value = f(value) }
      def modify[B](f: A => (A, B)): cats.Id[B] = {
        val (newA, b) = f(value)
        value = newA
        b
      }
      def getAndUpdate(f: A => A): cats.Id[A]   = {
        val old = value
        value = f(value)
        old
      }
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
  }

  /** Effect instance for scala.concurrent.Future (asynchronous execution).
    */
  def futureEffect(using ec: scala.concurrent.ExecutionContext): Effect[scala.concurrent.Future] =
    new Effect[scala.concurrent.Future] {
      import scala.concurrent.{Future, Promise, blocking}
      import java.util.concurrent.atomic.AtomicReference

      type Mutex = java.util.concurrent.Semaphore

      def createMutex: Mutex = new java.util.concurrent.Semaphore(1)

      def withLock[A](m: Mutex)(fa: Future[A]): Future[A] = {
        m.acquire()
        fa.andThen { case _ => m.release() }
      }

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

      def ref[A](initial: A): Future[Ref[Future, A]] = Future.successful(new Ref[Future, A] {
        private val underlying                   = new AtomicReference[A](initial)
        def get: Future[A]                       = Future.successful(underlying.get())
        def set(a: A): Future[Unit]              = Future.successful(underlying.set(a))
        def update(f: A => A): Future[Unit]      = Future.successful {
          var updated = false
          while !updated do {
            val current = underlying.get()
            updated = underlying.compareAndSet(current, f(current))
          }
        }
        def modify[B](f: A => (A, B)): Future[B] = Future.successful {
          var result: B = null.asInstanceOf[B]
          var updated   = false
          while !updated do {
            val current   = underlying.get()
            val (newA, b) = f(current)
            if underlying.compareAndSet(current, newA) then {
              result = b
              updated = true
            }
          }
          result
        }
        def getAndUpdate(f: A => A): Future[A]   = Future.successful {
          var old: A  = null.asInstanceOf[A]
          var updated = false
          while !updated do {
            old = underlying.get()
            updated = underlying.compareAndSet(old, f(old))
          }
          old
        }
      })

      def start[A](fa: Future[A]): Future[Fiber[Future, A]] = {
        val promise = Promise[Outcome[A]]()
        fa.onComplete {
          case scala.util.Success(a) => promise.success(Outcome.Succeeded(a))
          case scala.util.Failure(e) => promise.success(Outcome.Errored(e))
        }
        Future.successful(new Fiber[Future, A] {
          def cancel: Future[Unit]     = Future.successful(()) // Future can't be truly canceled
          def join: Future[Outcome[A]] = promise.future
        })
      }

      def guaranteeCase[A](fa: Future[A])(finalizer: Outcome[A] => Future[Unit]): Future[A] = {
        fa.transformWith { result =>
          val outcome = result match {
            case scala.util.Success(a) => Outcome.Succeeded(a)
            case scala.util.Failure(e) => Outcome.Errored(e)
          }
          finalizer(outcome).flatMap { _ =>
            result match {
              case scala.util.Success(a) => Future.successful(a)
              case scala.util.Failure(e) => Future.failed(e)
            }
          }
        }
      }
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
