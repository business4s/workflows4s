package workflows4s.runtime.instanceengine

import cats.effect.{IO, Sync, SyncIO}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import org.slf4j.MDC
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}

class LoggingWorkflowInstanceEngine(
    override protected val delegate: WorkflowInstanceEngine,
) extends DelegatingWorkflowInstanceEngine
    with StrictLogging {

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]] = {
    (IO(logger.trace(s"[${workflow.id}] queryState()")) *>
      delegate
        .queryState(workflow)
        .flatTap(state => IO(logger.trace(s"[${workflow.id}] queryState → $state")))
        .onError(e => IO(logger.error(s"[${workflow.id}] queryState failed", e)))).withMDC(workflow)
  }

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = {
    (IO(logger.trace(s"[${workflow.id}] getProgress()")) *>
      delegate
        .getProgress(workflow)
        .flatTap(prog => IO(logger.trace(s"[${workflow.id}] getProgress → $prog")))
        .onError(e => IO(logger.error(s"[${workflow.id}] getProgress failed", e))))
      .withMDC(workflow)
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      includeRedeliverable: Boolean = false,
  ): IO[List[SignalDef[?, ?]]] = {
    (IO(logger.trace(s"[${workflow.id}] getExpectedSignals(includeRedeliverable=$includeRedeliverable)")) *>
      delegate
        .getExpectedSignals(workflow, includeRedeliverable)
        .flatTap(signals => IO(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))
        .onError(e => IO(logger.error(s"[${workflow.id}] getExpectedSignals failed", e))))
      .withMDC(workflow)
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
  ): IO[WakeupResult[WCEvent[Ctx]]] = {
    (IO(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .flatMap({
          case WakeupResult.Noop              =>
            IO(logger.trace("⤷ Nothing to execute")).as(WakeupResult.Noop: WakeupResult[WCEvent[Ctx]])
          case WakeupResult.Processed(result) =>
            WakeupResult
              .Processed(
                IO(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
                  result
                    .flatTap({
                      case ProcessingResult.Proceeded(event)     =>
                        IO(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $event"))
                      case ProcessingResult.Failed(retry, event) =>
                        IO(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $retry, event: $event"))
                    })
                    .onError(e => IO(logger.error("wakeupEffect failed", e))),
              )
              .pure[IO]
        })
        .onError(e => IO(logger.error(s"[${workflow.id}] triggerWakeup failed", e))))
      .withMDC(workflow)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[SignalResult[WCEvent[Ctx], Resp]] = {
    (IO(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .flatMap({
          case SignalResult.Processed(resultIO) =>
            IO(logger.debug(s"[${workflow.id}] handleSignal → effect returned")).as(
              SignalResult.Processed(
                IO(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
                  resultIO
                    .flatMap(result =>
                      IO(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=${result.event}, resp=${result.response}"))
                        .as(result),
                    )
                    .onError(e => IO(logger.error("handleSignalEffect failed", e))),
              ),
            )
          case SignalResult.Redelivered(resp)   =>
            IO(logger.debug(s"[${workflow.id}] handleSignal → redelivered signal(${signalDef.name}), resp=$resp")) *>
              SignalResult.Redelivered(resp).pure[IO]
          case SignalResult.UnexpectedSignal    =>
            IO(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})")) *>
              SignalResult.UnexpectedSignal.pure[IO]
        })
        .onError(e => IO(logger.error(s"[${workflow.id}] handleSignal failed for ${signalDef.name}", e))))
      .withMDC(workflow)
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      event: WCEvent[Ctx],
  ): SyncIO[Option[ActiveWorkflow[Ctx]]] = {
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
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[PostExecCommand]] = {
    (IO(logger.debug(s"""[${oldState.id}] onStateChange:
                        |old state: ${oldState.liveState}
                        |new state: ${newState.liveState}""".stripMargin)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatTap(cmds => IO(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))
        .onError(e => IO(logger.error(s"[${oldState.id}] onStateChange failed", e)))).withMDC(newState)
  }

  extension [F[_]: {Sync as fSync}, A](ioa: F[A]) {
    def withMDC(activeWorkflow: ActiveWorkflow[? <: WorkflowContext]): F[A] = {
      fSync.delay {
        MDC.put("workflow_instance_id", activeWorkflow.id.instanceId)
        MDC.put("workflow_template_id", activeWorkflow.id.templateId)
      } *> fSync.guarantee(ioa, fSync.delay(MDC.clear()))
    }
  }

}
