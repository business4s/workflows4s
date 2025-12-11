package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

abstract class BasicEngine[F[_]](using E: Effect[F]) extends WorkflowInstanceEngine[F] {

  protected def now: F[Instant]

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] = {
    E.map(now)(workflow.proceed[F])
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = E.pure(workflow.handleSignal[F, Req, Resp](signalDef)(req))

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): F[Option[ActiveWorkflow[Ctx]]] =
    E.pure(workflow.handleEvent(event))

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = E.pure(workflow.liveState)

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = E.pure(workflow.progress)

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[List[SignalDef[?, ?]]] =
    E.pure(workflow.expectedSignals)

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = E.pure(Set.empty)
}
