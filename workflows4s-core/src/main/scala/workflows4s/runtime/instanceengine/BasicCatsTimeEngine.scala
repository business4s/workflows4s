package workflows4s.runtime.instanceengine

import cats.Applicative
import cats.effect.Clock
import cats.syntax.functor.*

import java.time.Instant

class BasicCatsTimeEngine[F[_]: Applicative](clock: Clock[F]) extends BasicEngine[F] {

  override protected def now: F[Instant] = clock.realTime.map(x => Instant.ofEpochMilli(x.toMillis))

}
