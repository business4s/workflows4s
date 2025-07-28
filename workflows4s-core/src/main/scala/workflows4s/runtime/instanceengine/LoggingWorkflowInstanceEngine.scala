package workflows4s.runtime.instanceengine

import cats.effect.{IO, SyncIO}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.{ActiveWorkflow, SignalDef, WCEvent, WCState, WorkflowContext}

import java.time.Instant

class LoggingWorkflowInstanceEngine(
    override protected val delegate: WorkflowInstanceEngine,
) extends DelegatingWorkflowInstanceEngine
    with StrictLogging {

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]] = {
    IO(logger.trace(s"[${workflow.id}] queryState()")) *>
      delegate
        .queryState(workflow)
        .flatTap(state => IO(logger.trace(s"[${workflow.id}] queryState → $state")))
  }

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = {
    IO(logger.trace(s"[${workflow.id}] getProgress()")) *>
      delegate
        .getProgress(workflow)
        .flatTap(prog => IO(logger.trace(s"[${workflow.id}] getProgress → $prog")))
  }

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]] = {
    IO(logger.trace(s"[${workflow.id}] getExpectedSignals()")) *>
      delegate
        .getExpectedSignals(workflow)
        .flatTap(signals => IO(logger.trace(s"[${workflow.id}] getExpectedSignals → [${signals.map(_.name).mkString(", ")}]")))
  }

  override def triggerWakeup[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
  ): IO[Option[IO[Either[Instant, WCEvent[Ctx]]]]] = {
    IO(logger.debug(s"[${workflow.id}] triggerWakeup()")) *>
      delegate
        .triggerWakeup(workflow)
        .map(_.map { inner =>
          IO(logger.trace(s"[${workflow.id}] ⤷ wakeupEffect starting")) *>
            inner
              .flatTap {
                case Left(timeout) =>
                  IO(logger.info(s"[${workflow.id}] ⤷ wakeupEffect failed with retry at $timeout"))
                case Right(evt)    =>
                  IO(logger.debug(s"[${workflow.id}] ⤷ wakeupEffect returned event: $evt"))
              }
        })
        .flatTap {
          case Some(_) =>
            IO(logger.debug(s"[${workflow.id}] triggerWakeup → scheduled effect"))
          case None    =>
            IO(logger.debug(s"[${workflow.id}] triggerWakeup → no wakeup needed"))
        }
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[Option[IO[(WCEvent[Ctx], Resp)]]] = {
    IO(logger.debug(s"[${workflow.id}] handleSignal(${signalDef.name}, $req)")) *>
      delegate
        .handleSignal(workflow, signalDef, req)
        .map(_.map { inner =>
          IO(logger.trace(s"[${workflow.id}] ⤷ handleSignalEffect start")) *>
            inner.flatTap { case (evt, resp) =>
              IO(logger.debug(s"[${workflow.id}] ⤷ handleSignalEffect result: event=$evt, resp=$resp"))
            }
        })
        .flatTap {
          case Some(_) => IO(logger.debug(s"[${workflow.id}] handleSignal → effect returned"))
          case None    => IO(logger.warn(s"[${workflow.id}] handleSignal → unexpected signal(${signalDef.name})"))
        }
  }

  override def handleEvent[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      event: WCEvent[Ctx],
  ): SyncIO[Option[ActiveWorkflow[Ctx]]] = {
    SyncIO(logger.debug(s"[${workflow.id}] handleEvent(event = $event)")) *>
      delegate
        .handleEvent(workflow, event)
        .flatMap {
          case Some(newWf) =>
            SyncIO(logger.debug(s"[${workflow.id}] handleEvent → new state"))
              .map(_ => Some(newWf))
          case None        =>
            SyncIO(logger.warn(s"[${workflow.id}] handleEvent → no state change"))
              .map(_ => None)
        }
  }

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[PostExecCommand]] = {
    IO(logger.debug(
      s"""[${oldState.id}] onStateChange:
         |old state: ${oldState.liveState}
         |new state: ${newState.liveState}""".stripMargin)) *>
      delegate
        .onStateChange(oldState, newState)
        .flatTap(cmds => IO(logger.trace(s"[${oldState.id}] onStateChange → commands: $cmds")))
  }

}
