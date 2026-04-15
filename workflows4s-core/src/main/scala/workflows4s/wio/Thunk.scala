package workflows4s.wio

import cats.Monad

/** A lazy, synchronous computation that may have side effects.
  *
  * Used where only sync side-effects are allowed (logging, clock reads) — never async/IO. This is the engine's hook point for event handling: engines
  * can compose sync logic (like logging) around pure event processing without requiring an async effect type.
  */
opaque type Thunk[+A] = () => A

object Thunk {
  def apply[A](body: => A): Thunk[A] = () => body
  def pure[A](a: A): Thunk[A]        = () => a

  extension [A](self: Thunk[A]) {
    def unsafeRun(): A = self()

    def onError(f: Throwable => Thunk[Unit]): Thunk[A] = () => {
      try self()
      catch {
        case e: Throwable =>
          f(e)()
          throw e
      }
    }
  }

  given Monad[Thunk] = new Monad[Thunk] {
    def pure[A](a: A): Thunk[A]                                 = Thunk.pure(a)
    def flatMap[A, B](fa: Thunk[A])(f: A => Thunk[B]): Thunk[B] = () => f(fa())()
    def tailRecM[A, B](a: A)(f: A => Thunk[Either[A, B]]): Thunk[B] = () => {
      @annotation.tailrec def loop(x: A): B = f(x)() match {
        case Left(next) => loop(next)
        case Right(b)   => b
      }
      loop(a)
    }
  }
}
