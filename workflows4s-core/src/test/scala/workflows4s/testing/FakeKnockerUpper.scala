package workflows4s.testing

import cats.effect.IO
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

class FakeKnockerUpper extends KnockerUpper.Agent[Unit] {

  private var wakeupAt: Option[Instant] = None
  def lastRegisteredWakeup: Option[Instant] = wakeupAt

  override def updateWakeup(id: Unit, at: Option[Instant]): IO[Unit] = IO(this.wakeupAt = at)
}
