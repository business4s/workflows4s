package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect

import java.time.{Clock, Instant}

class BasicJavaTimeEngine[F[_]](clock: Clock)(using E: Effect[F]) extends BasicEngine[F] {

  override protected def now: F[Instant] = E.delay(clock.instant())

}
