package workflows4s.cats

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.instanceengine.{Effect, Fiber, Outcome, Ref}

object CatsEffect {

  /** Effect instance for cats.effect.IO (asynchronous, non-blocking execution).
    */
  given ioEffect: Effect[IO] = new Effect[IO] {

    type Mutex = Semaphore[IO]

    def createMutex: IO[Mutex] = Semaphore[IO](1)

    def withLock[A](m: Mutex)(fa: => IO[A]): IO[A] = m.permit.use(_ => fa)

    def pure[A](a: A): IO[A]                                                = IO.pure(a)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]                      = fa.flatMap(f)
    def map[A, B](fa: IO[A])(f: A => B): IO[B]                              = fa.map(f)
    def raiseError[A](e: Throwable): IO[A]                                  = IO.raiseError(e)
    def handleErrorWith[A](fa: => IO[A])(f: Throwable => IO[A]): IO[A]      = fa.handleErrorWith(f)
    def sleep(duration: scala.concurrent.duration.FiniteDuration): IO[Unit] = IO.sleep(duration)
    def delay[A](a: => A): IO[A]                                            = IO.delay(a)

    def ref[A](initial: A): IO[Ref[IO, A]] = cats.effect.Ref[IO].of(initial).map { catsRef =>
      new Ref[IO, A] {
        def get: IO[A]                       = catsRef.get
        def set(a: A): IO[Unit]              = catsRef.set(a)
        def update(f: A => A): IO[Unit]      = catsRef.update(f)
        def modify[B](f: A => (A, B)): IO[B] = catsRef.modify(f)
        def getAndUpdate(f: A => A): IO[A]   = catsRef.getAndUpdate(f)
      }
    }

    def start[A](fa: IO[A]): IO[Fiber[IO, A]] = fa.start.map { catsFiber =>
      new Fiber[IO, A] {
        def cancel: IO[Unit]     = catsFiber.cancel
        def join: IO[Outcome[A]] = catsFiber.join.flatMap {
          case cats.effect.kernel.Outcome.Succeeded(fa) => fa.map(Outcome.Succeeded(_))
          case cats.effect.kernel.Outcome.Errored(e)    => IO.pure(Outcome.Errored(e))
          case cats.effect.kernel.Outcome.Canceled()    => IO.pure(Outcome.Canceled)
        }
      }
    }

    def guaranteeCase[A](fa: IO[A])(finalizer: Outcome[A] => IO[Unit]): IO[A] = {
      fa.guaranteeCase {
        case cats.effect.kernel.Outcome.Succeeded(fa) => fa.flatMap(a => finalizer(Outcome.Succeeded(a)))
        case cats.effect.kernel.Outcome.Errored(e)    => finalizer(Outcome.Errored(e))
        case cats.effect.kernel.Outcome.Canceled()    => finalizer(Outcome.Canceled)
      }
    }

    def runSyncUnsafe[A](fa: IO[A]): A = fa.unsafeRunSync()
  }
}
