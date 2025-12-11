package workflows4s.runtime.instanceengine

import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.internal.WakeupResult
import workflows4s.wio.{ActiveWorkflow, WCEvent, WorkflowContext}

import java.time.Instant

class WakingWorkflowInstanceEngine[F[_]](protected val delegate: WorkflowInstanceEngine[F], knockerUpper: KnockerUpper.Agent[F])(using
    val E: Effect[F],
) extends DelegatingWorkflowInstanceEngine[F]
    with StrictLogging {

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] = {
    type Event = WCEvent[Ctx]
    E.map(super.triggerWakeup(workflow)) { result =>
      if !result.hasEffect then WakeupResult.Noop[F]().asInstanceOf[WakeupResult[F, Event]]
      else {
        val processed                                          = result.asInstanceOf[WakeupResult.Processed[F, Event]]
        val wrappedIO: F[WakeupResult.ProcessingResult[Event]] = E.flatMap(processed.result) { processingResult =>
          val updateF = processingResult.toRaw match {
            case Right(_)        => E.unit
            case Left(retryTime) =>
              if workflow.wakeupAt.forall(_.isAfter(retryTime)) then updateWakeup(workflow, Some(retryTime))
              else E.unit
          }
          E.map(updateF)(_ => processingResult.asInstanceOf[WakeupResult.ProcessingResult[Event]])
        }
        WakeupResult.Processed[F, Event](wrappedIO)
      }
    }
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = {
    E.productL(
      super.onStateChange(oldState, newState),
      E.whenA(newState.wakeupAt != oldState.wakeupAt)(updateWakeup(newState, newState.wakeupAt)),
    )
  }

  private def updateWakeup(workflow: ActiveWorkflow[?], time: Option[Instant]): F[Unit] = {
    E.productR(
      E.void(E.delay(logger.debug(s"Registering wakeup for ${workflow.id} at $time"))),
      E.handleErrorWith(knockerUpper.updateWakeup(workflow.id, time))(err =>
        E.delay {
          logger.error("Failed to register wakeup", err)
        },
      ),
    )
  }

}
