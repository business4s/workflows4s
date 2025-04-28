package workflows4s.runtime.wakeup

import java.time.Instant

import cats.effect.IO

// https://en.wikipedia.org/wiki/Knocker-up
object KnockerUpper {

  trait Process[F[_], +Id, +Result] {
    def initialize(wakeUp: Id => F[Unit]): Result
  }

  // TODO we seem vulnerable to clash of ids between different workflows/runtimes?
  //   theoretically user could be expected to handle separation on their side (create many KUs) buts not great UX
  trait Agent[-Id] {
    def updateWakeup(id: Id, at: Option[Instant]): IO[Unit]
    def curried(id: Id): Agent.Curried = {
      val self = this
      val id0  = id
      (_: Any, at: Option[Instant]) => self.updateWakeup(id0, at)
    }
  }

  object Agent {
    type Curried = Agent[Unit]
  }

}
