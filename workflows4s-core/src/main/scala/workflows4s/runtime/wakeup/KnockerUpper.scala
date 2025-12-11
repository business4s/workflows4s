package workflows4s.runtime.wakeup

import workflows4s.effect.Effect

import java.time.Instant
import workflows4s.runtime.{WorkflowInstanceId, WorkflowRuntime}

// https://en.wikipedia.org/wiki/Knocker-up
object KnockerUpper {

  trait Process[F[_], +Result] {
    def initialize(wakeUp: WorkflowInstanceId => F[Unit]): Result

    def initialize(runtimes: Seq[WorkflowRuntime[F, ?]])(using E: Effect[F]): Result = {
      val asMap = runtimes.map(r => r.templateId -> r).toMap
      this.initialize(id => {
        asMap
          .get(id.templateId)
          .map(r => E.flatMap(r.createInstance(id.instanceId))(_.wakeup()))
          .getOrElse(E.raiseError(new RuntimeException(s"Runtime ${id.templateId} not found")))
      })
    }
  }

  trait Agent[F[_]] {
    def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit]
  }

}
