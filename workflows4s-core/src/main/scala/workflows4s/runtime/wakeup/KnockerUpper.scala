package workflows4s.runtime.wakeup

import java.time.Instant
import cats.effect.IO
import workflows4s.runtime.WorkflowInstanceId

// https://en.wikipedia.org/wiki/Knocker-up
object KnockerUpper {

  trait Process[F[_], +Result] {
    def initialize(wakeUp: WorkflowInstanceId => F[Unit]): Result
  }

  trait Agent {
    def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit]
  }

}
