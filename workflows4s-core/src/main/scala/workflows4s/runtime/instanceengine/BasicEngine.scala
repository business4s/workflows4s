package workflows4s.runtime.instanceengine

import cats.MonadThrow
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.functor.*
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

trait BasicEngine[F[_]: MonadThrow, Ctx <: WorkflowContext] extends WorkflowInstanceEngine[F, Ctx] {

  protected def now: F[Instant]

  override def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] = {
    now.map(workflow.proceed(_, liftWCEffect))
  }

  override def handleSignal[Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]] = {
    workflow.handleSignal(signalDef, req, liftWCEffect).pure[F]
  }

  override def handleEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]] =
    Thunk.pure(workflow.handleEvent(event))

  override def queryState(workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = workflow.liveState.pure[F]

  override def getProgress(workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] =
    workflow.progress.pure[F]

  override def getExpectedSignals(
      workflow: ActiveWorkflow[Ctx],
      includeRedeliverable: Boolean = false,
  ): F[List[SignalDef[?, ?]]] =
    workflow.expectedSignals(includeRedeliverable).pure[F]

  override def onStateChange(
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = Set.empty[WorkflowInstanceEngine.PostExecCommand].pure[F]
}
