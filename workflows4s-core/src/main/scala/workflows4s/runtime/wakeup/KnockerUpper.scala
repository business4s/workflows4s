package workflows4s.runtime.wakeup

import cats.MonadError

import java.time.Instant
import cats.syntax.all.*
import workflows4s.runtime.{WorkflowInstanceId, WorkflowRuntime}

// https://en.wikipedia.org/wiki/Knocker-up
object KnockerUpper {

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

  trait Agent[F[_]] {
    def updateWakeup(id: WorkflowInstanceId, at: Option[Instant]): F[Unit]
  }

}
