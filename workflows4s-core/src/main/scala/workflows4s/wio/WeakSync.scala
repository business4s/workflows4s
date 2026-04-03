package workflows4s.wio

import cats.effect.IO

/** Lifts a lazy synchronous computation into an effect type `F[_]`.
  *
  * This is the integration point between the engine's sync operations (logging, clock reads) and the user's chosen effect system. A minimal
  * alternative to `cats.effect.Sync` — only provides `delay`.
  */
trait WeakSync[F[_]] {
  def delay[A](body: => A): F[A]
}

object WeakSync {
  def apply[F[_]](using ev: WeakSync[F]): WeakSync[F] = ev

  given WeakSync[IO] with {
    override def delay[A](body: => A): IO[A] = IO(body)
  }
}
