package workflows4s.runtime.instanceengine

import cats.effect.{Clock, IO, SyncIO}
import cats.implicits.catsSyntaxApplicativeId
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.*

import java.time.Instant

class BasicCatsTimeEngine(clock: Clock[IO]) extends WorkflowInstanceEngine {

  private def now: IO[Instant] = clock.realTime.map(x => Instant.ofEpochMilli(x.toMillis))

  override def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[Option[IO[Either[Instant, WCEvent[Ctx]]]]] = {
    now.map(workflow.proceed)
  }

  override def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[Option[IO[(WCEvent[Ctx], Resp)]]] = workflow.handleSignal(signalDef)(req).pure[IO]

  override def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[Ctx]]] =
    workflow.handleEvent(event).pure[SyncIO]

  override def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]] = workflow.liveState.pure[IO]

  override def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]] = workflow.progress.pure[IO]

  override def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]] =
    workflow.expectedSignals.pure[IO]

  override def onStateChange[Ctx <: WorkflowContext](oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): IO[Set[WorkflowInstanceEngine.PostExecCommand]] = IO.pure(Set.empty)
}
