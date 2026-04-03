package workflows4s.wio

/** A lazy, synchronous computation that may have side effects.
  *
  * Used where only sync side-effects are allowed (logging, clock reads) — never async/IO. This is the engine's hook point for event handling: engines
  * can compose sync logic (like logging) around pure event processing without requiring an async effect type.
  */
final class Thunk[+A] private (private val run: () => A) {

  def unsafeRun(): A = run()

  def map[B](f: A => B): Thunk[B] = Thunk(f(unsafeRun()))

  def flatMap[B](f: A => Thunk[B]): Thunk[B] = Thunk(f(unsafeRun()).unsafeRun())

  def *>[B](next: Thunk[B]): Thunk[B] = flatMap(_ => next)

  def flatTap[B](f: A => Thunk[B]): Thunk[A] = flatMap(a => f(a).map(_ => a))

  def onError(f: Throwable => Thunk[Unit]): Thunk[A] = Thunk {
    try unsafeRun()
    catch {
      case e: Throwable =>
        f(e).unsafeRun()
        throw e
    }
  }

}

object Thunk {
  def apply[A](body: => A): Thunk[A] = new Thunk(() => body)
  def pure[A](a: A): Thunk[A]        = new Thunk(() => a)
}
