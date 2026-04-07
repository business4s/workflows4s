package workflows4s.runtime.instanceengine

import cats.MonadThrow
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{ActiveWorkflow, WCEvent, WeakSync, WorkflowContext}
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.internal.WakeupResult.ProcessingResult

import java.time.Instant

class WakingWorkflowInstanceEngine[F[_]: {MonadThrow, WeakSync}, Ctx <: WorkflowContext](
    protected val delegate: WorkflowInstanceEngine[F, Ctx],
    knockerUpper: KnockerUpper.Agent[F],
) extends DelegatingWorkflowInstanceEngine[F, Ctx]
    with StrictLogging {

  override def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] =
    super
      .triggerWakeup(workflow)
      .map({
        case WakeupResult.Noop()             => WakeupResult.Noop()
        case WakeupResult.Processed(eventIO) =>
          WakeupResult.Processed(for {
            result <- eventIO
            _      <- result match {
                        case ProcessingResult.Proceeded(_)         => MonadThrow[F].unit
                        case ProcessingResult.Failed(retryTime, _) =>
                          if workflow.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(workflow, Some(retryTime))
                          else MonadThrow[F].unit
                      }
          } yield result)
      })

  override def onStateChange(
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    super.onStateChange(oldState, newState) <*
      MonadThrow[F].whenA(newState.wakeupAt != oldState.wakeupAt)(updateWakeup(newState, newState.wakeupAt))
  }

  private def updateWakeup(workflow: ActiveWorkflow[?], time: Option[Instant]) = {
    WeakSync[F].delay(logger.debug(s"Registering wakeup for ${workflow.id} at $time")) *>
      knockerUpper
        .updateWakeup(workflow.id, time)
        .handleError(err => {
          logger.error("Failed to register wakeup", err)
        })
  }

}
