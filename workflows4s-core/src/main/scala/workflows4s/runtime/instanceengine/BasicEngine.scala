package workflows4s.runtime.instanceengine

import cats.effect.{IO, SyncIO}
import cats.implicits.catsSyntaxApplicativeId
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

trait BasicEngine extends WorkflowInstanceEngine {

  protected def now: IO[Instant]

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WakeupResult[WCEvent[Ctx]]] = {
    now.map(workflow.proceed)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[SignalResult[WCEvent[Ctx], Resp]] = workflow.handleSignal(signalDef)(req).pure[IO]

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[Ctx]]] =
    workflow.handleEvent(event).pure[SyncIO]

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]] = workflow.liveState.pure[IO]

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = workflow.progress.pure[IO]

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]] =
    workflow.expectedSignals.pure[IO]

  override def onStateChange[Ctx <: WorkflowContext](
      oldState: ActiveWorkflow[Ctx],
      newState: ActiveWorkflow[Ctx],
  ): IO[Set[WorkflowInstanceEngine.PostExecCommand]] = IO.pure(Set.empty)
}
