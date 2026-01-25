package workflows4s.wio

import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.internal.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant

case class ActiveWorkflow[Ctx <: WorkflowContext](id: WorkflowInstanceId, wio: WIO.Initial[Ctx], initialState: WCState[Ctx]) {
  lazy val wakeupAt: Option[Instant] = GetWakeupEvaluator.extractNearestWakeup(wio)

  lazy val staticState: WCState[Ctx] = GetStateEvaluator.extractLastState(wio, (), initialState).getOrElse(initialState)

  def liveState: WCState[Ctx] = {
    val wf = effectlessProceed
    wf.staticState
  }

  def expectedSignals(includeRedeliverable: Boolean = false): List[SignalDef[?, ?]] = {
    val wf = effectlessProceed
    SignalEvaluator.getExpectedSignals(wf.wio, includeRedeliverable)
  }

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SignalResult[WCEvent[Ctx], Resp] = {
    val wf = effectlessProceed
    SignalEvaluator.handleSignal(signalDef, req, wf.wio, wf.staticState)
  }

  def handleEvent(event: WCEvent[Ctx]): Option[ActiveWorkflow[Ctx]] = {
    val wf = effectlessProceed
    EventEvaluator
      .handleEvent(event, wf.wio, initialState)
      .newWorkflow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed)
  }

  def proceed(now: Instant): WakeupResult[WCEvent[Ctx]] = {
    val wf = effectlessProceed
    RunIOEvaluator.proceed(wf.wio, wf.staticState, now)
  }

  def progress: WIOExecutionProgress[WCState[Ctx]] = effectlessProceed.wio.toProgress

  // moves forward as far as possible
  private def effectlessProceed: ActiveWorkflow[Ctx] =
    ProceedEvaluator
      .proceed(wio, initialState)
      .newFlow
      .map(newWio => this.copy(wio = newWio))
      .map(x => x.effectlessProceed)
      .getOrElse(this)
}
