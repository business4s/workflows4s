package workflows4s.runtime.instanceengine

import cats.effect.{Sync, SyncIO}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

class LoggingWorkflowInstanceEngine[F[_]: Sync](
    override protected val delegate: WorkflowInstanceEngine[F],
) extends DelegatingWorkflowInstanceEngine[F]
    with StrictLogging {

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WCState[Ctx]] = {
    (Sync[F].delay(logger.trace(s"[${workflow.id}] queryState()")) *>
      delegate
        .queryState(workflow)
        .flatTap(state => Sync[F].delay(logger.trace(s"[${workflow.id}] queryState → $state")))
        .onError(e => Sync[F].delay(logger.error(s"[${workflow.id}] queryState failed", e)))).withMDC(workflow)
  }

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = {
    (Sync[F].delay(logger.trace(s"[${workflow.id}] getProgress()")) *>
      delegate
        .getProgress(workflow)
        .flatTap(prog => Sync[F].delay(logger.trace(s"[${workflow.id}] getProgress → $prog")))
        .onError(e => Sync[F].delay(logger.error(s"[${workflow.id}] getProgress failed", e))))
      .withMDC(workflow)
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
      includeRedeliverable: Boolean = false,
  ): F[List[SignalDef[?, ?]]] = {
    (Sync[F].delay(logger.trace(s"[${workflow.id}] getExpectedSignals(includeRedeliverable=$includeRedeliverable)")) *>
      delegate
        .getExpectedSignals(workflow, includeRedeliverable)
        .flatTap(signals => Sync[F].delay(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))
        .onError(e => Sync[F].delay(logger.error(s"[${workflow.id}] getExpectedSignals failed", e))))
      .withMDC(workflow)
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
  ): F[WakeupResult[F, WCEvent[Ctx]]] = {
    (Sync[F].delay(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .flatMap({
          case WakeupResult.Noop()            =>
            Sync[F].delay(logger.trace("⤷ Nothing to execute")).as(WakeupResult.Noop(): WakeupResult[F, WCEvent[Ctx]])
          case WakeupResult.Processed(result) =>
            WakeupResult
              .Processed(
                Sync[F].delay(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
                  result
                    .flatTap({
                      case ProcessingResult.Proceeded(event)     =>
                        Sync[F].delay(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event"))
                      case ProcessingResult.Failed(retry, event) =>
                        Sync[F].delay(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $retry, event: $event"))
                    })
                    .onError(e => Sync[F].delay(logger.error("wakeupEffect failed", e))),
              )
              .pure[F]
        })
        .onError(e => Sync[F].delay(logger.error(s"[${workflow.id}] triggerWakeup failed", e))))
      .withMDC(workflow)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = {
    (Sync[F].delay(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .flatMap({
          case SignalResult.Processed(resultIO) =>
            Sync[F]
              .delay(logger.debug(s"[${workflow.id}] handleSignal → effect returned"))
              .as(
                SignalResult.Processed(
                  Sync[F].delay(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
                    resultIO
                      .flatMap(result =>
                        Sync[F]
                          .delay(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}"))
                          .as(result),
                      )
                      .onError(e => Sync[F].delay(logger.error("handleSignalEffect failed", e))),
                ),
              )
          case SignalResult.Redelivered(resp)   =>
            Sync[F].delay(logger.debug(s"[${workflow.id}] handleSignal → redelivered signal(${signalDef.name}), resp=$resp")) *>
              (SignalResult.Redelivered(resp): SignalResult[F, WCEvent[Ctx], Resp]).pure[F]
          case SignalResult.UnexpectedSignal()  =>
            Sync[F].delay(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")) *>
              (SignalResult.UnexpectedSignal(): SignalResult[F, WCEvent[Ctx], Resp]).pure[F]
        })
        .onError(e => Sync[F].delay(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))))
      .withMDC(workflow)
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
      event: WCEvent[Ctx],
  ): SyncIO[Option[ActiveWorkflow[F, Ctx]]] = {
    (SyncIO(logger.debug(s"[${workflow.id}] handleEvent(event = $event)")) *>
      delegate
        .handleEvent(workflow, event)
        .flatMap {
          case Some(newWf) => SyncIO(logger.debug(s"[${workflow.id}] handleEvent → new state")) *> SyncIO.pure(Some(newWf))
          case None        => SyncIO(logger.warn(s"[${workflow.id}] handleEvent → no state change")) *> SyncIO.pure(None)
        }
        .onError(e => SyncIO(logger.error(s"[${workflow.id}] handleEvent failed", e)))).withMDC(workflow)
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[PostExecCommand]] = {
    (Sync[F].delay(logger.debug(s"""[${oldState.id}] onStateChange:
                                   |old state: ${oldState.liveState}
                                   |new state: ${newState.liveState}""".stripMargin)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatTap(cmds => Sync[F].delay(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))
        .onError(e => Sync[F].delay(logger.error(s"[${oldState.id}] onStateChange failed", e)))).withMDC(newState)
  }

  extension [G[_]: {Sync as gSync}, A](ioa: G[A]) {
    def withMDC(activeWorkflow: ActiveWorkflow[?, ? <: WorkflowContext]): G[A] = {
      gSync.delay {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      } *> gSync.guarantee(ioa, gSync.delay(MDC.clear()))
    }
  }

}
