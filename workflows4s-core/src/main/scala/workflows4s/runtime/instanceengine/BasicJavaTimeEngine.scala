package workflows4s.runtime.instanceengine

import cats.effect.Sync

import java.time.{Clock, Instant}

class BasicJavaTimeEngine[F[_]: Sync](clock: Clock) extends BasicEngine[F] {

  override protected def now: F[Instant] = Sync[F].delay(clock.instant())

}
