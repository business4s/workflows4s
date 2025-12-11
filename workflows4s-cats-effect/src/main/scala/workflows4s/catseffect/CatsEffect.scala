package workflows4s.catseffect

import cats.effect.IO
import workflows4s.effect.Effect

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Effect instance for cats.effect.IO.
  *
  * This provides the Effect type class instance for cats-effect IO, enabling workflows4s to work with the cats-effect ecosystem.
  *
  * Import this object's givens to use IO-based workflows: {{{ import workflows4s.catseffect.CatsEffect.given }}}
  *
  * Or import all cats-effect support: {{{ import workflows4s.catseffect.given }}}
  */
object CatsEffect {

  given ioEffect: Effect[IO] = new Effect[IO] {
    def pure[A](a: A): IO[A]                                        = IO.pure(a)
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]              = fa.flatMap(f)
    def map[A, B](fa: IO[A])(f: A => B): IO[B]                      = fa.map(f)
    def raiseError[A](e: Throwable): IO[A]                          = IO.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.handleErrorWith(f)
    def sleep(duration: FiniteDuration): IO[Unit]                   = IO.sleep(duration)
    def realTimeInstant: IO[Instant]                                = IO.realTimeInstant
    def delay[A](a: => A): IO[A]                                    = IO.delay(a)
  }
}
