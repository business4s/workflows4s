package workflows4s.wio

import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.internal.*
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.runtime.instanceengine.Effect
import java.time.Instant

case class ActiveWorkflow[F[_], Ctx <: WorkflowContext](
    id: WorkflowInstanceId,
    wio: WIO.Initial[F, Ctx],
    initialState: WCState[Ctx],
)(using E: Effect[F]) { // Added Effect here

  lazy val wakeupAt: Option[Instant] =
    GetWakeupEvaluator.extractNearestWakeup[F, Ctx, Any, Nothing, WCState[Ctx]](wio)

  lazy val staticState: WCState[Ctx] =
    GetStateEvaluator.extractLastState[F, Ctx, Any, Nothing, WCState[Ctx]](wio, (), initialState).getOrElse(initialState)

  def liveState: WCState[Ctx] = effectlessProceed.staticState

  def expectedSignals: List[SignalDef[?, ?]] =
    GetSignalDefsEvaluator.run[F, Ctx, Any, Nothing, WCState[Ctx]](effectlessProceed.wio)

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResult[WCEvent[Ctx], Resp, F] = {
    val wf = effectlessProceed
    SignalEvaluator.handleSignal[Ctx, Req, Resp, F, Any, WCState[Ctx]](signalDef, req, wf.wio, (), wf.staticState)
  }

  def handleEvent(event: WCEvent[Ctx]): Option[ActiveWorkflow[F, Ctx]] = {
    val wf = effectlessProceed
    EventEvaluator
      .handleEvent[F, Ctx, Any, Nothing, WCState[Ctx]](event, wf.wio, initialState)
      .newWorkflow
      .map(newWio => this.copy(wio = newWio))
      .map(_.effectlessProceed)
  }

  def proceed(now: Instant): WakeupResult[WCEvent[Ctx], F] = {
    val wf = effectlessProceed
    RunIOEvaluator.proceed[Ctx, F, WCState[Ctx]](wf.wio, wf.staticState, now)
  }

  def progress: WIOExecutionProgress[WCState[Ctx]] =
    effectlessProceed.wio.toProgress

  private def effectlessProceed: ActiveWorkflow[F, Ctx] =
    ProceedEvaluator
      .proceed[F, Ctx](wio, initialState)
      .newFlow
      .map(newWio => this.copy(wio = newWio))
      .map(_.effectlessProceed)
      .getOrElse(this)
}
