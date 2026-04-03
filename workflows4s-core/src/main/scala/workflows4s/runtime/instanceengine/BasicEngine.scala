package workflows4s.runtime.instanceengine

import cats.Applicative
import cats.effect.SyncIO
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.functor.*
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

trait BasicEngine[F[_]: Applicative] extends WorkflowInstanceEngine[F] {

  protected def now: F[Instant]

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] = {
    now.map(workflow.proceed)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = workflow.handleSignal(signalDef)(req).pure[F]

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[F, Ctx]]] =
    workflow.handleEvent(event).pure[SyncIO]

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WCState[Ctx]] = workflow.liveState.pure[F]

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] =
    workflow.progress.pure[F]

  override def getExpectedSignals[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[F, Ctx],
      includeRedeliverable: Boolean = false,
  ): F[List[SignalDef[?, ?]]] =
    workflow.expectedSignals(includeRedeliverable).pure[F]

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[F, Ctx],
      newState: ActiveWorkflow[F, Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = Set.empty[WorkflowInstanceEngine.PostExecCommand].pure[F]
}
