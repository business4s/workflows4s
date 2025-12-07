package workflows4s.effect.concurrent

import workflows4s.effect.Effect

/** Extended effect capabilities for concurrent operations.
  *
  * Provides fiber spawning, cancellation, and finalization support.
  */
trait Concurrent[F[_]] extends Effect[F] {

  /** Starts execution of `fa` in a new fiber. */
  def start[A](fa: F[A]): F[Fiber[F, A]]

  /** Executes `fa` with a finalizer that runs based on the outcome. */
  def guaranteeCase[A](fa: F[A])(finalizer: Outcome[F, A] => F[Unit]): F[A]

  /** Executes `fa` with a finalizer that always runs. */
  def guarantee[A](fa: F[A])(finalizer: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => finalizer)

  /** Executes `fa` and `fb` concurrently, returning both results. */
  def both[A, B](fa: F[A], fb: F[B]): F[(A, B)]

  /** Races two effects, returning the first to complete. */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]]
}

object Concurrent {
  def apply[F[_]](using c: Concurrent[F]): Concurrent[F] = c
}

/** A fiber represents a running computation that can be cancelled or joined. */
trait Fiber[F[_], A] {

  /** Waits for the fiber to complete and returns its result. */
  def join: F[Outcome[F, A]]

  /** Cancels the fiber. */
  def cancel: F[Unit]
}

/** The outcome of a fiber computation. */
enum Outcome[F[_], A] {

  /** Computation completed successfully with a value wrapped in F. */
  case Succeeded[G[_], B](fa: G[B]) extends Outcome[G, B]

  /** Computation failed with an exception. */
  case Errored[G[_], B](e: Throwable) extends Outcome[G, B]

  /** Computation was cancelled. */
  case Canceled[G[_], B]() extends Outcome[G, B]
}
