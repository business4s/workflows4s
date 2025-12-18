package workflows4s.runtime.instanceengine

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.{ActiveWorkflow, WCEvent, WorkflowContext}
import java.time.Instant

class WakingWorkflowInstanceEngine[F[_]](
    protected val delegate: WorkflowInstanceEngine[F],
    knockerUpper: KnockerUpper.Agent[F],
)(using E: Effect[F])
    extends DelegatingWorkflowInstanceEngine[F]
    with StrictLogging {

  import Effect.*

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[WCEvent[Ctx], F]] =
    super
      .triggerWakeup(workflow)
      .map {
        case WakeupResult.Noop()            => WakeupResult.Noop()
        case WakeupResult.Processed(eventF) =>
          WakeupResult.Processed(for {
            result <- eventF
            _      <- result match {
                        case ProcessingResult.Proceeded(_) =>
                          E.unit

                        case ProcessingResult.Delayed(retryTime) =>
                          if workflow.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(workflow, Some(retryTime))
                          else E.unit

                        case ProcessingResult.Failed(error) =>
                          // Handle actual system failures (throwables)
                          E.delay(logger.error(s"Wakeup processing failed for ${workflow.id}", error))
                      }
          } yield result)
      }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <*
      E.whenA(newState.wakeupAt != oldState.wakeupAt)(updateWakeup(newState, newState.wakeupAt))
  }

  private def updateWakeup(workflow: ActiveWorkflow[F, ?], time: Option[Instant]): F[Unit] = {
    val log = E.delay(logger.debug(s"Registering wakeup for ${workflow.id} at $time"))

    (log *> knockerUpper.updateWakeup(workflow.id, time))
      .handleErrorWith(err => E.delay(logger.error("Failed to register wakeup", err)))
  }
}
