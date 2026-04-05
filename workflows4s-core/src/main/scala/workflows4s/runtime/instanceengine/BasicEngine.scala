package workflows4s.runtime.instanceengine

import cats.Applicative
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.functor.*
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

trait BasicEngine[F[_]: Applicative] extends WorkflowInstanceEngine[F] {

  protected def now: F[Instant]

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[WCEffect[Ctx], WCEvent[Ctx]]] = {
    now.map(workflow.proceed)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEffect[Ctx], WCEvent[Ctx], Resp]] =
    workflow.handleSignal(signalDef)(req).pure[F]

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]] =
    Thunk.pure(workflow.handleEvent(event))

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = workflow.liveState.pure[F]

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] =
    workflow.progress.pure[F]

  override def getExpectedSignals[Ctx <: WorkflowContext](
      workflow: ActiveWorkflow[Ctx],
      includeRedeliverable: Boolean = false,
  ): F[List[SignalDef[?, ?]]] =
    workflow.expectedSignals(includeRedeliverable).pure[F]

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): F[Set[WorkflowInstanceEngine.PostExecCommand]] = Set.empty[WorkflowInstanceEngine.PostExecCommand].pure[F]
}
