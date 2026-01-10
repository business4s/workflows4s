package workflows4s.runtime.instanceengine

import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Clock
import scala.annotation.unused

trait WorkflowInstanceEngine[F[_]](using Effect[F]) {
  import Effect.*
  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): WCState[Ctx]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): WIOExecutionProgress[WCState[Ctx]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): List[SignalDef[?, ?]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[WCEvent[Ctx], F]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[WCEvent[Ctx], Resp, F]]

  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): F[Option[ActiveWorkflow[F, Ctx]]]

  def onStateChange[Ctx <: WorkflowContext](
      @unused oldState: ActiveWorkflow[F, Ctx],
      @unused newState: ActiveWorkflow[F, Ctx],
  ): F[Set[PostExecCommand]]

  // Process event synchronously and return the new state
  // This ensures event handling is atomic and deterministic
  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): F[ActiveWorkflow[F, Ctx]] = this
    .handleEvent(workflow, event)
    .map(_.getOrElse(workflow))

}

object WorkflowInstanceEngine {
  def builder[F[_]](using Effect[F]) = new WorkflowInstanceEngineBuilder[F] {}

  def default[F[_]: Effect](
      knockerUpper: KnockerUpper.Agent[F],
      registry: WorkflowRegistry.Agent[F],
      clock: Clock = Clock.systemUTC(),
  ): WorkflowInstanceEngine[F] =
    builder[F]
      .withJavaTime(clock)
      .withWakeUps(knockerUpper)
      .withRegistering(registry)
      .withGreedyEvaluation
      .withLogging
      .get

  def basic[F[_]: Effect](clock: Clock = Clock.systemUTC()): WorkflowInstanceEngine[F] = builder[F]
    .withJavaTime(clock)
    .withoutWakeUps
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  enum PostExecCommand {
    case WakeUp
  }
}
