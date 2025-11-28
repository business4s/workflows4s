package workflows4s.runtime.wakeup

import cats.Id
import cats.effect.IO
import cats.syntax.all._
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.registry.WorkflowRegistry.ExecutionStatus
import workflows4s.runtime.registry.WorkflowSearch

import scala.concurrent.duration.FiniteDuration

/** A poller that periodically checks the WorkflowRegistry for workflows in `Awaiting` status and sends wakeups to them.
  *
  * This is designed to work with single-step evaluation engines where:
  *   - Signal responses return quickly (after first step)
  *   - Subsequent steps are driven by this poller checking the registry
  *
  * Usage:
  * {{{
  * val poller = RegistryBasedWakeupPoller(
  *   registry = myRegistry,
  *   templateId = "my-workflow",
  *   pollInterval = 100.millis,
  *   lookupInstance = id => workflowRuntime.getInstance(id)
  * )
  *
  * // Start polling in background
  * poller.pollForever.start
  * }}}
  */
trait RegistryBasedWakeupPoller {

  /** Poll once and send wakeups to all workflows in Awaiting status. Returns the number of workflows woken up. */
  def pollOnce: IO[Int]

  /** Poll forever with the configured interval. */
  def pollForever: IO[Nothing]

}

object RegistryBasedWakeupPoller {

  /** Create a poller for IO-based workflow instances (e.g., InMemoryWorkflowInstance). */
  def apply[State](
      registry: WorkflowSearch[IO],
      templateId: String,
      pollInterval: FiniteDuration,
      lookupInstance: String => Option[WorkflowInstance[IO, State]],
  ): RegistryBasedWakeupPoller = create(
    registry = registry,
    templateId = templateId,
    pollInterval = pollInterval,
    lookupInstance = lookupInstance,
    wakeup = (instance: WorkflowInstance[IO, State]) => instance.wakeup(),
  )

  /** Create a poller for synchronous (Id-based) workflow instances (e.g., InMemorySyncWorkflowInstance). */
  def forSync[State](
      registry: WorkflowSearch[IO],
      templateId: String,
      pollInterval: FiniteDuration,
      lookupInstance: String => Option[WorkflowInstance[Id, State]],
  ): RegistryBasedWakeupPoller = create(
    registry = registry,
    templateId = templateId,
    pollInterval = pollInterval,
    lookupInstance = lookupInstance,
    wakeup = (instance: WorkflowInstance[Id, State]) => IO(instance.wakeup()),
  )

  private def create[F[_], State](
      registry: WorkflowSearch[IO],
      templateId: String,
      pollInterval: FiniteDuration,
      lookupInstance: String => Option[WorkflowInstance[F, State]],
      wakeup: WorkflowInstance[F, State] => IO[Unit],
  ): RegistryBasedWakeupPoller = new RegistryBasedWakeupPoller {

    private val awaitingQuery: WorkflowSearch.Query = WorkflowSearch.Query(
      status = Set(ExecutionStatus.Awaiting),
    )

    override def pollOnce: IO[Int] = {
      for {
        results <- registry.search(templateId, awaitingQuery)
        wokenUp <- results.traverse { result =>
                     lookupInstance(result.id.instanceId) match {
                       case Some(instance) => wakeup(instance).as(1)
                       case None           => IO.pure(0)
                     }
                   }
      } yield wokenUp.sum
    }

    override def pollForever: IO[Nothing] = {
      (pollOnce *> IO.sleep(pollInterval)).foreverM
    }
  }

}
