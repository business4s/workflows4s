package workflows4s.runtime.wakeup

import cats.MonadError

import java.time.Instant
import cats.effect.IO
import cats.syntax.all.*
import workflows4s.runtime.{WorkflowInstanceId, WorkflowRuntime}

/** Schedules wakeup calls for workflows that have pending timers.
  *
  * Named after the [[https://en.wikipedia.org/wiki/Knocker-up historical profession]]. The [[Agent]] registers/cancels wakeups; the [[Process]]
  * bridges the scheduling backend to the actual wakeup calls on workflow instances.
  */
object KnockerUpper {

  /** Background process that executes scheduled wakeups. Must be initialized with a callback that wakes up a given instance. */
  trait Process[F[_], +Result] {
    def initialize(wakeUp: WorkflowInstanceId => F[Unit]): Result

    def initialize(runtimes: Seq[WorkflowRuntime[F, ?]])(using me: MonadError[F, Throwable]): Result = {
      val asMap = runtimes.map(r => r.templateId -> r).toMap
      this.initialize(id => {
        asMap
          .get(id.templateId)
          .map(_.createInstance(id.instanceId).flatMap(_.wakeup()))
          .getOrElse(me.raiseError(new RuntimeException(s"Runtime ${id.templateId} not found")))
      })
    }
  }

  /** Called by the engine after each state change to register or cancel the next wakeup. `None` cancels any pending wakeup. */
  trait Agent {
    def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): IO[Unit]
  }

}
