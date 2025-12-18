package workflows4s.runtime.instanceengine

import cats.effect.{Clock, IO}

import java.time.Instant

class BasicCatsTimeEngine(clock: Clock[IO])(using Effect[IO]) extends BasicEngine[IO] {

  override protected def now: IO[Instant] = clock.realTime.map(x => Instant.ofEpochMilli(x.toMillis))

}
