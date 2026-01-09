package workflows4s.runtime.wakeup

import cats.Id
import cats.effect.IO
import cats.syntax.all.*
import workflows4s.runtime.{WorkflowInstance, WorkflowRuntime}
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

  def create[F[_], State](
      registry: WorkflowSearch[IO],
      pollInterval: FiniteDuration,
      runtime: WorkflowRuntime[IO, ?],
  ): RegistryBasedWakeupPoller = new RegistryBasedWakeupPoller {

    private val awaitingQuery: WorkflowSearch.Query = WorkflowSearch.Query(
      status = Set(ExecutionStatus.AwaitingNextStep),
    )

    override def pollOnce: IO[Int] = {
      for {
        results <- registry.search(runtime.templateId, awaitingQuery)
        _       <- results.traverse { result =>
                     runtime.createInstance(result.id.instanceId).flatMap(_.wakeup())
                   }
      } yield results.size
    }

    override def pollForever: IO[Nothing] = {
      (pollOnce *> IO.sleep(pollInterval)).foreverM
    }
  }

}
