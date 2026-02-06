package workflows4s.runtime.instanceengine

import cats.effect.{IO, SyncIO}
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
  * Use [[WorkflowInstanceEngine.default]] for production (includes wakeup scheduling, registry, greedy evaluation, and logging)
  * or [[WorkflowInstanceEngine.basic]] for simpler setups without external integrations.
  * Custom engines can be composed via [[WorkflowInstanceEngineBuilder]].
  */
trait WorkflowInstanceEngine {

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], includeRedeliverable: Boolean = false): IO[List[SignalDef[?, ?]]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WakeupResult[WCEvent[Ctx]]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[SignalResult[WCEvent[Ctx], Resp]]

  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[Ctx]]]

  def onStateChange[Ctx <: WorkflowContext](@unused oldState: ActiveWorkflow[Ctx], @unused newState: ActiveWorkflow[Ctx]): IO[Set[PostExecCommand]]

  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[ActiveWorkflow[Ctx]] = this
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
  def basic(clock: Clock = Clock.systemUTC()): WorkflowInstanceEngine                                               = builder
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
