package workflows4s.runtime.instanceengine

import cats.MonadThrow
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult, WeakSync}

class LoggingWorkflowInstanceEngine[F[_]: MonadThrow, Ctx <: WorkflowContext](
    override protected val delegate: WorkflowInstanceEngine[F, Ctx],
) extends DelegatingWorkflowInstanceEngine[F, Ctx]
    with StrictLogging {

  private def logF(body: => Unit): F[Unit] = WeakSync.delay[F](body)

  override def queryState(workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = {
    (logF(logger.trace(s"[${workflow.id}] queryState()")) *>
      delegate
        .queryState(workflow)
        .flatTap(state => logF(logger.trace(s"[${workflow.id}] queryState → $state")))
        .onError(e => logF(logger.error(s"[${workflow.id}] queryState failed", e)))).withMDC(workflow)
  }

  override def getProgress(workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = {
    (logF(logger.trace(s"[${workflow.id}] getProgress()")) *>
      delegate
        .getProgress(workflow)
        .flatTap(prog => logF(logger.trace(s"[${workflow.id}] getProgress → $prog")))
        .onError(e => logF(logger.error(s"[${workflow.id}] getProgress failed", e))))
      .withMDC(workflow)
  }

  override def getExpectedSignals(
      workflow: ActiveWorkflow[Ctx],
      includeRedeliverable: Boolean = false,
  ): F[List[SignalDef[?, ?]]] = {
    (logF(logger.trace(s"[${workflow.id}] getExpectedSignals(includeRedeliverable=$includeRedeliverable)")) *>
      delegate
        .getExpectedSignals(workflow, includeRedeliverable)
        .flatTap(signals => logF(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))
        .onError(e => logF(logger.error(s"[${workflow.id}] getExpectedSignals failed", e))))
      .withMDC(workflow)
  }

  override def triggerWakeup(
      workflow: ActiveWorkflow[Ctx],
  ): F[WakeupResult[F, WCEvent[Ctx]]] = {
    (logF(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .flatMap({
          case WakeupResult.Noop()            =>
            logF(logger.trace("⤷ Nothing to execute")).as(WakeupResult.Noop(): WakeupResult[F, WCEvent[Ctx]])
          case WakeupResult.Processed(result) =>
            WakeupResult
              .Processed(
                logF(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
                  result
                    .flatTap({
                      case ProcessingResult.Proceeded(event)     =>
                        logF(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event"))
                      case ProcessingResult.Failed(retry, event) =>
                        logF(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $retry, event: $event"))
                    })
                    .onError(e => logF(logger.error("wakeupEffect failed", e))),
              )
              .pure[F]
        })
        .onError(e => logF(logger.error(s"[${workflow.id}] triggerWakeup failed", e))))
      .withMDC(workflow)
  }

  override def handleSignal[Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = {
    (logF(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .flatMap({
          case SignalResult.Processed(resultIO) =>
            logF(logger.debug(s"[${workflow.id}] handleSignal → effect returned"))
              .as(
                SignalResult.Processed(
                  logF(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
                    resultIO
                      .flatTap(result =>
                        logF(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}")),
                      )
                      .onError(e => logF(logger.error("handleSignalEffect failed", e))),
                ),
              )
          case SignalResult.Redelivered(resp)   =>
            logF(logger.debug(s"[${workflow.id}] handleSignal → redelivered signal(${signalDef.name}), resp=$resp")) *>
              (SignalResult.Redelivered(resp): SignalResult[F, WCEvent[Ctx], Resp]).pure[F]
          case SignalResult.UnexpectedSignal()  =>
            logF(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")) *>
              (SignalResult.UnexpectedSignal(): SignalResult[F, WCEvent[Ctx], Resp]).pure[F]
        })
        .onError(e => logF(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))))
      .withMDC(workflow)
  }

  override def handleEvent(
      workflow: ActiveWorkflow[Ctx],
      event: WCEvent[Ctx],
  ): Thunk[Option[ActiveWorkflow[Ctx]]] = {
    (Thunk(logger.debug(s"[${workflow.id}] handleEvent(event = $event)")) *>
      delegate
        .handleEvent(workflow, event)
        .flatMap {
          case Some(newWf) => Thunk(logger.debug(s"[${workflow.id}] handleEvent → new state")) *> Thunk.pure(Some(newWf))
          case None        => Thunk(logger.warn(s"[${workflow.id}] handleEvent → no state change")) *> Thunk.pure(None)
        }
        .onError(e => Thunk(logger.error(s"[${workflow.id}] handleEvent failed", e)))).withThunkMDC(workflow)
  }

  override def onStateChange(
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[PostExecCommand]] = {
    (logF(logger.debug(s"""[${oldState.id}] onStateChange:
                          |old state: ${oldState.liveState}
                          |new state: ${newState.liveState}""".stripMargin)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatTap(cmds => logF(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))
        .onError(e => logF(logger.error(s"[${oldState.id}] onStateChange failed", e)))).withMDC(newState)
  }

  extension [A](fa: F[A]) {
    def withMDC(activeWorkflow: ActiveWorkflow[Ctx]): F[A] = {
      logF {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      } *> fa
        .flatTap(_ => logF(MDC.clear()))
        .onError(_ => logF(MDC.clear()))
    }
  }

  extension [A](thunk: Thunk[A]) {
    def withThunkMDC(activeWorkflow: ActiveWorkflow[Ctx]): Thunk[A] = {
      Thunk {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      } *> Thunk {
        try thunk.unsafeRun()
        finally MDC.clear()
      }
    }
  }

}
