package workflows4s.runtime.instanceengine

import cats.Functor
import cats.effect.Clock
import cats.syntax.functor.*

import java.time.Instant

class BasicCatsTimeEngine[F[_]: Functor](clock: Clock[F]) extends BasicEngine[F] {

  override protected def now: F[Instant] = clock.realTime.map(x => Instant.ofEpochMilli(x.toMillis))

}
