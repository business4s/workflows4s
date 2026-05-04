package workflows4s.runtime.instanceengine

import cats.Functor
import cats.syntax.functor.*
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

/** Transforms an engine from effect G to effect F using a natural transformation. */
class MappedWorkflowInstanceEngine[F[_]: Functor, G[_], Ctx <: WorkflowContext](
    delegate: WorkflowInstanceEngine[G, Ctx],
    nat: [A] => G[A] => F[A],
) extends WorkflowInstanceEngine[F, Ctx] {

  def liftWCEffect: WCEffectLift[Ctx, F] = [A] => (fa: WCEffect[Ctx][A]) => nat(delegate.liftWCEffect(fa))

  def queryState(workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]] = nat(delegate.queryState(workflow))

  def getProgress(workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]] = nat(delegate.getProgress(workflow))

  def getExpectedSignals(workflow: ActiveWorkflow[Ctx], includeRedeliverable: Boolean): F[List[SignalDef[?, ?]]] =
    nat(delegate.getExpectedSignals(workflow, includeRedeliverable))

  def triggerWakeup(workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]] =
    nat(delegate.triggerWakeup(workflow)).map(_.mapK(nat))

  def handleSignal[Req, Resp](workflow: ActiveWorkflow[Ctx], signalDef: SignalDef[Req, Resp], req: Req): F[SignalResult[F, WCEvent[Ctx], Resp]] =
    nat(delegate.handleSignal(workflow, signalDef, req)).map(_.mapK(nat))

  def handleEvent(workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): Thunk[Option[ActiveWorkflow[Ctx]]] =
    delegate.handleEvent(workflow, event)

  def onStateChange(oldState: ActiveWorkflow[Ctx], newState: ActiveWorkflow[Ctx]): F[Set[WorkflowInstanceEngine.PostExecCommand]] =
    nat(delegate.onStateChange(oldState, newState))
}
