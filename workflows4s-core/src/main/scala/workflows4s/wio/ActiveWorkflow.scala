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
)(using E: Effect[F]) {

  lazy val wakeupAt: Option[Instant] =
    GetWakeupEvaluator.extractNearestWakeup(wio)

  lazy val staticState: WCState[Ctx] =
    GetStateEvaluator.extractLastState(wio, (), initialState).getOrElse(initialState)

  def liveState: WCState[Ctx] = effectlessProceed.staticState

  def expectedSignals: List[SignalDef[?, ?]] =
    GetSignalDefsEvaluator.run(effectlessProceed.wio)

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResult[WCEvent[Ctx], Resp, F] = {
    val wf = effectlessProceed
    SignalEvaluator.handleSignal(signalDef, req, wf.wio, wf.staticState)
  }

  def handleEvent(event: WCEvent[Ctx]): Option[ActiveWorkflow[F, Ctx]] = {
    val wf = effectlessProceed
    EventEvaluator
      .handleEvent(event, wf.wio, initialState)
      .newWorkflow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed)
  }

  def proceed(now: Instant): WakeupResult[WCEvent[Ctx], F] = {
    val wf = effectlessProceed
    RunIOEvaluator.proceed(wf.wio, wf.staticState, now)
  }

  def progress: WIOExecutionProgress[WCState[Ctx]] =
    effectlessProceed.wio.toProgress

  private def effectlessProceed: ActiveWorkflow[F, Ctx] =
    ProceedEvaluator
      .proceed[F, Ctx](wio, initialState)
      .newFlow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed)
      .getOrElse(this)
}
