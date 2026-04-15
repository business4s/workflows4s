package workflows4s.wio.internal

import cats.Monad
import cats.syntax.functor.*

/** Suspends a synchronous `body` inside `F[_]` via `Monad[F].unit.map(_ => body)`. Internal helper — call sites use it to mark synchronous side
  * effects without requiring a dedicated `Sync`/`Defer` typeclass.
  *
  * Not meant for general usage! It should be used only in components that can be easily swapped by the user.
  */
object WeakSync {
  def delay[F[_]](using F: Monad[F])[A](body: => A): F[A] = F.unit.map(_ => body)
}
