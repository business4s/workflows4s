package workflows4s.runtime.wakeup

import java.time.Instant

import cats.effect.IO

object NoOpKnockerUpper {

  object Agent extends KnockerUpper.Agent[Any] {
    override def updateWakeup(id: Any, at: Option[Instant]): IO[Unit] = IO.unit
  }
}
