package workflows4s.runtime.wakeup

import cats.effect.IO

import java.time.Instant

object NoOpKnockerUpper {

  object Agent extends KnockerUpper.Agent[Any] {
    override def updateWakeup(id: Any, at: Option[Instant]): IO[Unit] = IO.unit
  }
}
