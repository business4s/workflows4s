package workflow4s.runtime.wakeup

import java.time.Instant

import cats.effect.IO

object NoOpKnockerUpper extends KnockerUpper {

  override def registerWakeup(at: Instant): IO[Unit] = IO.unit

  val factory: KnockerUpper.Factory[Any] = _ => this
}
