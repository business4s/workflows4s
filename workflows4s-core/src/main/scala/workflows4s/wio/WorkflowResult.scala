package workflows4s.wio

import java.time.Instant

/** WorkflowResult is a Free monad-like ADT that captures workflow operations for deferred interpretation into any effect system.
  */
enum WorkflowResult[+A] {
  case Pure(value: A)
  case Fail(error: Throwable)
  case Defer(thunk: () => A)
  case FlatMap[B, +A](base: WorkflowResult[B], f: B => WorkflowResult[A])                   extends WorkflowResult[A]
  case HandleError[B, +A](base: WorkflowResult[B], handler: Throwable => WorkflowResult[A]) extends WorkflowResult[A]
  case RealTime                                                                             extends WorkflowResult[Instant]

  // Monadic operations
  def flatMap[B](f: A => WorkflowResult[B]): WorkflowResult[B] =
    WorkflowResult.FlatMap(this, f)

  def map[B](f: A => B): WorkflowResult[B] =
    flatMap(a => WorkflowResult.Pure(f(a)))

  def recover[A1 >: A](f: Throwable => WorkflowResult[A1]): WorkflowResult[A1] =
    WorkflowResult.HandleError(this, f)

  def attempt: WorkflowResult[Either[Throwable, A]] =
    map(Right(_)).recover(e => WorkflowResult.Pure(Left(e)))
}

object WorkflowResult {
  def pure[A](a: A): WorkflowResult[A]             = Pure(a)
  def fail[A](error: Throwable): WorkflowResult[A] = Fail(error)
  def defer[A](thunk: => A): WorkflowResult[A]     = Defer(() => thunk)
  def realTime: WorkflowResult[Instant]            = RealTime
  def unit: WorkflowResult[Unit]                   = Pure(())

  def sequence[A](results: List[WorkflowResult[A]]): WorkflowResult[List[A]] =
    results.foldRight(pure(List.empty[A])) { (ra, acc) =>
      ra.flatMap(a => acc.map(a :: _))
    }

  def traverse[A, B](as: List[A])(f: A => WorkflowResult[B]): WorkflowResult[List[B]] =
    sequence(as.map(f))
}
