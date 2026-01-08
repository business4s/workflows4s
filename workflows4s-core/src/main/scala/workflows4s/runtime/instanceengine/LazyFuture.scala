package workflows4s.runtime.instanceengine

import scala.concurrent.{Await, ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success}

/** A lazy wrapper around Future that defers execution until explicitly run. Unlike standard Scala Future which starts executing immediately upon
  * creation, LazyFuture only creates and runs the underlying Future when `run` is called.
  *
  * ==Cancellation Limitations==
  * LazyFuture provides weaker cancellation semantics than effect systems like cats-effect IO. Scala Future does not support true cancellation - once
  * a Future starts executing, it runs to completion. When a LazyFuture fiber is canceled:
  *   - The fiber's outcome is marked as `Outcome.Canceled`
  *   - Subsequent calls to `join` will see the canceled outcome
  *   - However, the underlying computation continues running in the background
  *
  * This is an inherent limitation of the JVM Future model and cannot be fixed without cooperation from the running code (e.g., checking cancellation
  * tokens periodically).
  */
final case class LazyFuture[A](private val thunk: () => Future[A]) {

  def run: Future[A] = thunk()
}

object LazyFuture {

  def successful[A](a: A): LazyFuture[A] = LazyFuture(() => Future.successful(a))

  def failed[A](e: Throwable): LazyFuture[A] = LazyFuture(() => Future.failed(e))

  def delay[A](a: => A): LazyFuture[A] = LazyFuture { () =>
    try Future.successful(a)
    catch { case e: Throwable => Future.failed(e) }
  }

  def fromFuture[A](f: => Future[A]): LazyFuture[A] = LazyFuture(() => f)

  given lazyFutureEffect(using ec: ExecutionContext): Effect[LazyFuture] =
    new Effect[LazyFuture] {

      type Mutex = Semaphore

      def createMutex: LazyFuture[Mutex] = LazyFuture.successful(new Semaphore(1))

      def withLock[A](m: Mutex)(fa: => LazyFuture[A]): LazyFuture[A] = LazyFuture { () =>
        Future(blocking(m.acquire()))
          .flatMap { _ =>
            fa.run.transformWith { result =>
              m.release()
              Future.fromTry(result)
            }
          }
      }

      def pure[A](a: A): LazyFuture[A] = LazyFuture.successful(a)

      def flatMap[A, B](fa: LazyFuture[A])(f: A => LazyFuture[B]): LazyFuture[B] =
        LazyFuture(() => fa.run.flatMap(a => f(a).run))

      def map[A, B](fa: LazyFuture[A])(f: A => B): LazyFuture[B] =
        LazyFuture(() => fa.run.map(f))

      def raiseError[A](e: Throwable): LazyFuture[A] = LazyFuture.failed(e)

      def handleErrorWith[A](fa: => LazyFuture[A])(f: Throwable => LazyFuture[A]): LazyFuture[A] =
        LazyFuture(() => fa.run.recoverWith { case e => f(e).run })

      def sleep(duration: scala.concurrent.duration.FiniteDuration): LazyFuture[Unit] =
        LazyFuture(() => Future(blocking(Thread.sleep(duration.toMillis))))

      def delay[A](a: => A): LazyFuture[A] = LazyFuture.delay(a)

      def ref[A](initial: A): LazyFuture[Ref[LazyFuture, A]] = LazyFuture.successful(new Ref[LazyFuture, A] {
        private val underlying = new AtomicReference[A](initial)

        def get: LazyFuture[A]          = LazyFuture.successful(underlying.get())
        def set(a: A): LazyFuture[Unit] = LazyFuture.successful(underlying.set(a))

        def update(f: A => A): LazyFuture[Unit] = LazyFuture.successful {
          underlying.updateAndGet(x => f(x))
          ()
        }

        def modify[B](f: A => (A, B)): LazyFuture[B] = LazyFuture.successful {
          var result: B = null.asInstanceOf[B]
          underlying.updateAndGet { current =>
            val (next, b) = f(current)
            result = b
            next
          }
          result
        }

        def getAndUpdate(f: A => A): LazyFuture[A] = LazyFuture.successful(underlying.getAndUpdate(x => f(x)))
      })

      def start[A](fa: LazyFuture[A]): LazyFuture[Fiber[LazyFuture, A]] = LazyFuture { () =>
        val promise       = Promise[Outcome[A]]()
        val runningFuture = fa.run

        runningFuture.onComplete {
          case Success(a) => promise.trySuccess(Outcome.Succeeded(a))
          case Failure(e) => promise.trySuccess(Outcome.Errored(e))
        }

        Future.successful(new Fiber[LazyFuture, A] {

          /** Mark this fiber as canceled. Note: This does NOT stop the underlying computation, which continues running. It only affects the outcome
            * returned by `join`. See LazyFuture class documentation for details on cancellation limitations.
            */
          def cancel: LazyFuture[Unit]     = LazyFuture.successful {
            val _ = promise.trySuccess(Outcome.Canceled)
            ()
          }
          def join: LazyFuture[Outcome[A]] = LazyFuture.fromFuture(promise.future)
        })
      }

      def guaranteeCase[A](fa: LazyFuture[A])(finalizer: Outcome[A] => LazyFuture[Unit]): LazyFuture[A] =
        LazyFuture { () =>
          fa.run.transformWith { result =>
            val outcome = result match {
              case Success(a) => Outcome.Succeeded(a)
              case Failure(e) => Outcome.Errored(e)
            }
            finalizer(outcome).run.flatMap(_ => Future.fromTry(result))
          }
        }

      def runSyncUnsafe[A](fa: LazyFuture[A]): A = Await.result(fa.run, Duration.Inf)
    }
}
