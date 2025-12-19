package workflows4s.doobie

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstanceBase, WorkflowInstanceId}
import workflows4s.wio.*

/** Database-backed workflow instance using IO effect type directly.
  */
class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[IO, Ctx],
    storage: WorkflowStorage[IO, WCEvent[Ctx]],
    protected val engine: WorkflowInstanceEngine[IO],
) extends WorkflowInstanceBase[IO, Ctx]
    with StrictLogging {

  override protected def getWorkflow: IO[ActiveWorkflow[IO, Ctx]] = {
    storage
      .getEvents(id)
      .evalFold(baseWorkflow)((state, event) => engine.processEvent(state, event))
      .compile
      .lastOrError
  }

  override protected def persistEvent(event: WCEvent[Ctx]): IO[Unit] = storage.saveEvent(id, event)

  override protected def updateState(newState: ActiveWorkflow[IO, Ctx]): IO[Unit] = IO.unit

  override protected def lockState[T](update: ActiveWorkflow[IO, Ctx] => IO[T]): IO[T] =
    storage.lockWorkflow(id).use(_ => getWorkflow.flatMap(update))
}
