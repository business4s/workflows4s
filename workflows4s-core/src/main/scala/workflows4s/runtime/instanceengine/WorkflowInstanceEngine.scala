package workflows4s.runtime.instanceengine

import cats.MonadThrow
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
  * Engine[F, Ctx] is "how to run workflows from Ctx in F". It carries the ability to lift workflow effects (WCEffect[Ctx]) into the engine effect F.
  *
  * Use [[WorkflowInstanceEngine.default]] for production (includes wakeup scheduling, registry, greedy evaluation, and logging) or
  * [[WorkflowInstanceEngine.basic]] for simpler setups without external integrations. Custom engines can be composed via
  * [[WorkflowInstanceEngineBuilder]].
  */
trait WorkflowInstanceEngine[F[_], Ctx <: WorkflowContext] {

  def liftWCEffect: WCEffectLift[Ctx, F]

  def queryState(workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]]

  def getProgress(workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals(workflow: ActiveWorkflow[Ctx], includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]]

  def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]]

  def handleSignal[Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]]

  def handleEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]]

  def onStateChange(
      @unused oldState: ActiveWorkflow[Ctx],
      @unused newState: ActiveWorkflow[Ctx],
  ): F[Set[PostExecCommand]]

  def processEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[ActiveWorkflow[Ctx]] = this
    .handleEvent(workflow, event)
    .map(_.getOrElse(workflow))

  def mapK[G[_]: cats.Functor](nat: [A] => F[A] => G[A]): WorkflowInstanceEngine[G, Ctx] =
    MappedWorkflowInstanceEngine(this, nat)

}

object WorkflowInstanceEngine {
  val builder                                                             = WorkflowInstanceEngineBuilder
  def default[F[_]: MonadThrow, Ctx <: WorkflowContext](
      knockerUpper: KnockerUpper.Agent[F],
      registry: WorkflowRegistry.Agent[F],
      clock: Clock = Clock.systemUTC(),
  )(using ev: LiftWorkflowEffect[Ctx, F]): WorkflowInstanceEngine[F, Ctx] =
    builder[F](clock)
      .withWakeUps(knockerUpper)
      .withRegistering(registry)
      .withGreedyEvaluation
      .withLogging
      .get(ev.asPoly)
  def basic[F[_]: MonadThrow, Ctx <: WorkflowContext](
      clock: Clock = Clock.systemUTC(),
  )(using ev: LiftWorkflowEffect[Ctx, F]): WorkflowInstanceEngine[F, Ctx] = builder[F](clock)
    .withoutWakeUps
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get(ev.asPoly)

  enum PostExecCommand {
    case WakeUp
  }
}
