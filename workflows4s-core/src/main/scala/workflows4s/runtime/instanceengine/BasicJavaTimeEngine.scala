package workflows4s.runtime.instanceengine

import cats.effect.IO

import java.time.{Clock, Instant}

class BasicJavaTimeEngine(clock: Clock) extends BasicEngine {

  override protected def now: IO[Instant] = IO(clock.instant())

}
