package workflows4s.runtime.instanceengine

import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.internal.WakeupResult.ProcessingResult

class LoggingWorkflowInstanceEngine[F[_]](
    override protected val delegate: WorkflowInstanceEngine[F],
)(using val E: Effect[F])
    extends DelegatingWorkflowInstanceEngine[F]
    with StrictLogging {

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = {
    withMDC(workflow)(
      E.productR(
        E.delay(logger.trace(s"[${workflow.id}] queryState()")),
        E.onError(
          E.flatMap(delegate.queryState(workflow))(state => E.map(E.delay(logger.trace(s"[${workflow.id}] queryState → $state")))(_ => state)),
        )(e => E.delay(logger.error(s"[${workflow.id}] queryState failed", e))),
      ),
    )
  }

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = {
    withMDC(workflow)(
      E.productR(
        E.delay(logger.trace(s"[${workflow.id}] getProgress()")),
        E.onError(
          E.flatMap(delegate.getProgress(workflow))(prog => E.map(E.delay(logger.trace(s"[${workflow.id}] getProgress → $prog")))(_ => prog)),
        )(e => E.delay(logger.error(s"[${workflow.id}] getProgress failed", e))),
      ),
    )
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[List[SignalDef[?, ?]]] = {
    withMDC(workflow)(
      E.productR(
        E.delay(logger.trace(s"[${workflow.id}] getExpectedSignals()")),
        E.onError(
          E.flatMap(delegate.getExpectedSignals(workflow))(signals =>
            E.map(E.delay(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))(_ => signals),
          ),
        )(e => E.delay(logger.error(s"[${workflow.id}] getExpectedSignals failed", e))),
      ),
    )
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
  ): F[WakeupResult[F, WCEvent[Ctx]]] = {
    type Event = WCEvent[Ctx]
    withMDC(workflow)(
      E.productR(
        E.delay(logger.debug(s"[${workflow.id}] triggerWakeup()")),
        E.onError(
          E.flatMap(delegate.triggerWakeup(workflow)) { result =>
            if !result.hasEffect then E.map(E.delay(logger.trace("⤷ Nothing to execute")))(_ =>
              WakeupResult.Noop[F]().asInstanceOf[WakeupResult[F, Event]],
            )
            else {
              val processed                                              = result.asInstanceOf[WakeupResult.Processed[F, Event]]
              val wrappedResult: F[WakeupResult.ProcessingResult[Event]] = E.productR(
                E.delay(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")),
                E.onError(
                  E.flatMap(processed.result) { processingResult =>
                    processingResult.toRaw match {
                      case Right(event) =>
                        E.map(E.delay(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event")))(_ =>
                          processingResult.asInstanceOf[WakeupResult.ProcessingResult[Event]],
                        )
                      case Left(retry)  =>
                        E.map(E.delay(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $retry")))(_ =>
                          ProcessingResult.Failed(retry).asInstanceOf[WakeupResult.ProcessingResult[Event]],
                        )
                    }
                  },
                )(e => E.delay(logger.error("wakeupEffect failed", e))),
              )
              E.pure(WakeupResult.Processed[F, Event](wrappedResult))
            }
          },
        )(e => E.delay(logger.error(s"[${workflow.id}] triggerWakeup failed", e))),
      ),
    )
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = {
    type Event = WCEvent[Ctx]
    withMDC(workflow)(
      E.productR(
        E.delay(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")),
        E.onError(
          E.flatMap(delegate.handleSignal(workflow, signalDef, req)) { result =>
            if !result.hasEffect then E.productR(
              E.delay(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")),
              E.pure(SignalResult.UnexpectedSignal[F]().asInstanceOf[SignalResult[F, Event, Resp]]),
            )
            else {
              val processed = result.asInstanceOf[SignalResult.Processed[F, Event, Resp]]
              E.map(E.delay(logger.debug(s"[${workflow.id}] handleSignal → effect returned"))) { _ =>
                val wrappedIO: F[SignalResult.ProcessingResult[Event, Resp]] = E.productR(
                  E.delay(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")),
                  E.onError(
                    E.flatMap(processed.resultIO)(result =>
                      E.map(E.delay(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}")))(
                        _ => result.asInstanceOf[SignalResult.ProcessingResult[Event, Resp]],
                      ),
                    ),
                  )(e => E.delay(logger.error("handleSignalEffect failed", e))),
                )
                SignalResult.Processed[F, Event, Resp](wrappedIO)
              }
            }
          },
        )(e => E.delay(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))),
      ),
    )
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      event: WCEvent[Ctx],
  ): F[Option[ActiveWorkflow[Ctx]]] = {
    withMDC(workflow)(
      E.productR(
        E.delay(logger.debug(s"[${workflow.id}] handleEvent(event = $event)")),
        E.onError(
          E.flatMap(delegate.handleEvent(workflow, event)) {
            case Some(newWf) =>
              E.productR(E.delay(logger.debug(s"[${workflow.id}] handleEvent → new state")), E.pure(Some(newWf)))
            case None        =>
              E.productR(E.delay(logger.warn(s"[${workflow.id}] handleEvent → no state change")), E.pure(None))
          },
        )(e => E.delay(logger.error(s"[${workflow.id}] handleEvent failed", e))),
      ),
    )
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[PostExecCommand]] = {
    withMDC(newState)(
      E.productR(
        E.delay(logger.debug(s"""[${oldState.id}] onStateChange:
                                |old state: ${oldState.liveState}
                                |new state: ${newState.liveState}""".stripMargin)),
        E.onError(
          E.flatMap(delegate.onStateChange(oldState, newState))(cmds =>
            E.map(E.delay(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))(_ => cmds),
          ),
        )(e => E.delay(logger.error(s"[${oldState.id}] onStateChange failed", e))),
      ),
    )
  }

  private def withMDC[A](activeWorkflow: ActiveWorkflow[? <: WorkflowContext])(fa: F[A]): F[A] = {
    E.guarantee(
      E.productR(
        E.delay {
          MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
          MDC.put("workflow_template_id", activeWorkflow.id.templateId)
        },
        fa,
      ),
      E.delay(MDC.clear()),
    )
  }

}
