package workflow4s.runtime.wakeup

import cats.effect.IO

import java.time.Instant

object NoOpKnockerUpper extends KnockerUpper {

  override def registerWakeup(at: Instant): IO[Unit] = IO.unit

  val factory: KnockerUpper.Factory[Any] = _ => this
}
