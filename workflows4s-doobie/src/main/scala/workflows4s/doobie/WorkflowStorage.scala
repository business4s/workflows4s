package workflows4s.doobie

import cats.effect.kernel.Resource
import workflows4s.runtime.WorkflowInstanceId

/** Effect-polymorphic workflow storage. The F[_] effect type is typically IO.
  */
trait WorkflowStorage[F[_], Event] {

  def getEvents(id: WorkflowInstanceId): fs2.Stream[F, Event]
  def saveEvent(id: WorkflowInstanceId, event: Event): F[Unit]

  // Resource because some locking mechanisms might require an explicit release
  def lockWorkflow(id: WorkflowInstanceId): Resource[F, Unit]

}
