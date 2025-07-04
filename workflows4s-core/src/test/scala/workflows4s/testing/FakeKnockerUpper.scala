package workflows4s.testing

import cats.effect.IO
import workflows4s.runtime.wakeup.KnockerUpper

import java.time.Instant

class FakeKnockerUpper[Id] extends KnockerUpper.Agent[Id] {

  private var wakeups: Map[Id, Option[Instant]]     = Map()
  def lastRegisteredWakeup(id: Id): Option[Instant] = wakeups.get(id).flatten

  override def updateWakeup(id: Id, at: Option[Instant]): IO[Unit] = IO(this.wakeups = wakeups.updatedWith(id)(_ => Some(at)))
}
