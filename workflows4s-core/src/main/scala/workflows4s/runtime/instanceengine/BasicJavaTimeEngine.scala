package workflows4s.runtime.instanceengine

import cats.Applicative
import workflows4s.wio.WeakSync

import java.time.{Clock, Instant}

class BasicJavaTimeEngine[F[_]: {Applicative, WeakSync}](clock: Clock) extends BasicEngine[F] {

  override protected def now: F[Instant] = WeakSync[F].delay(clock.instant())

}
