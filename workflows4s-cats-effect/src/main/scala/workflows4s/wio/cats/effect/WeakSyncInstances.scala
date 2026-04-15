package workflows4s.wio.cats.effect

import _root_.cats.effect.Sync
import workflows4s.wio.WeakSync

object WeakSyncInstances {
  given [F[_]](using F: Sync[F]): WeakSync[F] with {
    override def delay[A](body: => A): F[A] = F.delay(body)
  }
}
