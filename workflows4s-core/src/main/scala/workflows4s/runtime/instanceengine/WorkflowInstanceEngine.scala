package workflows4s.runtime.instanceengine

import cats.effect.IO
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.runtime.registry.WorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Clock
import scala.annotation.unused

/** Strategy for evaluating workflow steps. Controls how signals, events, and wakeups are processed.
  *
  * Use [[WorkflowInstanceEngine.default]] for production (includes wakeup scheduling, registry, greedy evaluation, and logging) or
  * [[WorkflowInstanceEngine.basic]] for simpler setups without external integrations. Custom engines can be composed via
  * [[WorkflowInstanceEngineBuilder]].
  */
trait WorkflowInstanceEngine[F[_]] {

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WCState[Ctx]]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx]): F[WakeupResult[F, WCEvent[Ctx]]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[F, Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]]

  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[F, Ctx]]]

  def onStateChange[Ctx <: WorkflowContext](
      @unused oldState: ActiveWorkflow[F, Ctx],
      @unused newState: ActiveWorkflow[F, Ctx],
  ): F[Set[PostExecCommand]]

  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[F, Ctx], event: WCEvent[Ctx]): Thunk[ActiveWorkflow[F, Ctx]] = this
    .handleEvent(workflow, event)
    .map(_.getOrElse(workflow))

}

object WorkflowInstanceEngine {
  val builder                                                                                                       = WorkflowInstanceEngineBuilder
  def default(knockerUpper: KnockerUpper.Agent, registry: WorkflowRegistry.Agent, clock: Clock = Clock.systemUTC()) =
    builder
      .withJavaTime(clock)
      .withWakeUps(knockerUpper)
      .withRegistering(registry)
      .withGreedyEvaluation
      .withLogging
      .get
  def basic(clock: Clock = Clock.systemUTC()): WorkflowInstanceEngine[IO]                                           = builder
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
