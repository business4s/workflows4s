package workflows4s.runtime.instanceengine

import cats.effect.{Clock, IO}

import java.time.Instant

class BasicCatsTimeEngine(clock: Clock[IO]) extends BasicEngine {

  override protected def now: IO[Instant] = clock.realTime.map(x => Instant.ofEpochMilli(x.toMillis))

}
