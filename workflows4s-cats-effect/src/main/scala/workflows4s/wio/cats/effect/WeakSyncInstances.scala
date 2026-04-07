package workflows4s.wio.cats.effect

import _root_.cats.effect.IO
import workflows4s.wio.WeakSync

object WeakSyncInstances {
  given WeakSync[IO] with {
    override def delay[A](body: => A): IO[A] = IO(body)
  }
}
