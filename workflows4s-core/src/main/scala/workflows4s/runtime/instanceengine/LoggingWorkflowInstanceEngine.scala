package workflows4s.runtime.instanceengine

import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

class LoggingWorkflowInstanceEngine[F[_]](
    override protected val delegate: WorkflowInstanceEngine[F],
)(using E: Effect[F])
    extends DelegatingWorkflowInstanceEngine[F]
    with StrictLogging {

  import Effect.*

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): WCState[Ctx] = {
    logger.trace(s"[${workflow.id}] queryState()")
    val state = delegate.queryState(workflow)
    logger.trace(s"[${workflow.id}] queryState → $state")
    state
  }

  override def getProgress[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
  ): WIOExecutionProgress[WCState[Ctx]] = {
    logger.trace(s"[${workflow.id}] getProgress()")
    val progress = delegate.getProgress(workflow)
    logger.trace(s"[${workflow.id}] getProgress → $progress")
    progress
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): List[SignalDef[?, ?]] = {
    logger.trace(s"[${workflow.id}] getExpectedSignals()")
    val signals = delegate.getExpectedSignals(workflow)
    logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")
    signals
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
  ): F[WakeupResult[WCEvent[Ctx], F]] = {
    (E.delay(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .flatMap({
          case _: WakeupResult.Noop[?, ?] =>
            E.delay(logger.trace("⤷ Nothing to execute")).as(WakeupResult.noop[WCEvent[Ctx], F])

          case WakeupResult.Processed(result) =>
            val loggedResult = E.delay(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
              result
                .flatMap(res =>
                  E.delay(res match {
                    case ProcessingResult.Proceeded(event) =>
                      logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event")
                    case ProcessingResult.Delayed(at)      =>
                      logger.debug(s"[${workflow.id}] ⤷ wakeupEffect delayed until $at")
                    case ProcessingResult.Failed(err)      =>
                      logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with error: $err")
                  }).as(res),
                )
                .onError(e => E.delay(logger.error("wakeupEffect failed", e)))

            E.pure(WakeupResult.Processed[WCEvent[Ctx], F](loggedResult))
        })
        .onError(e => E.delay(logger.error(s"[${workflow.id}] triggerWakeup failed", e))))
      .withMDC(workflow)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp, F]] = {
    (E.delay(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .flatMap({
          case SignalResult.Processed(resultF) =>
            E.delay(logger.debug(s"[${workflow.id}] handleSignal → effect returned"))
              .as(
                SignalResult.Processed(
                  E.delay(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
                    resultF
                      .flatMap(result =>
                        E.delay(
                          logger.debug(
                            s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}",
                          ),
                        ).as(result),
                      )
                      .onError(e => E.delay(logger.error("handleSignalEffect failed", e))),
                ),
              )
          case SignalResult.UnexpectedSignal() =>
            E.delay(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")) *>
              E.pure(SignalResult.unexpected)
        })
        .onError(e => E.delay(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))))
      .withMDC(workflow)
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
      event: WCEvent[Ctx],
  ): F[Option[ActiveWorkflow[F, Ctx]]] = {
    (E.delay(logger.debug(s"[${workflow.id}] handleEvent(event = $event)")) *>
      delegate
        .handleEvent(workflow, event)
        .flatMap({
          case Some(newWf) => E.delay(logger.debug(s"[${workflow.id}] handleEvent → new state")).as(Some(newWf))
          case None        => E.delay(logger.warn(s"[${workflow.id}] handleEvent → no state change")).as(None)
        })
        .onError(e => E.delay(logger.error(s"[${workflow.id}] handleEvent failed", e))))
      .withMDC(workflow)
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[PostExecCommand]] = {
    val logMsg = s"""[${oldState.id}] onStateChange:
                    |old state: ${oldState.liveState}
                    |new state: ${newState.liveState}""".stripMargin

    (E.delay(logger.debug(logMsg)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatMap(cmds => E.delay(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")).as(cmds))
        .onError(e => E.delay(logger.error(s"[${oldState.id}] onStateChange failed", e))))
      .withMDC(newState)
  }

  // Helper extension for MDC handling using the Effect trait
  extension [A](fa: F[A]) {
    private def withMDC(activeWorkflow: ActiveWorkflow[F, ? <: WorkflowContext]): F[A] = {
      val setup   = E.delay {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      }
      val cleanup = E.delay(MDC.clear())

      // Ensure MDC is cleared even if 'fa' fails or is interrupted
      E.productR(setup, E.guarantee(fa, cleanup))
    }
  }
}
